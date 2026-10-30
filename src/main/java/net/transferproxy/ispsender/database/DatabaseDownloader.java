package net.transferproxy.ispsender.database;

import com.maxmind.db.Reader;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the IPinfo Lite database and installs it into an {@link IPInfoDatabase}.
 *
 * <p>The file is written to a temporary file, validated by opening it, and only then moved over the
 * previous database. A failed download therefore never breaks a working installation.
 */
public final class DatabaseDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseDownloader.class);
    private static final String TOKEN_PATTERN = "{token}";
    private static final String USER_AGENT = "ISPSender (TransferProxy plugin)";

    private final ISPSenderConfiguration config;
    private final Path target;
    private final HttpClient client;
    private final AtomicBoolean downloading = new AtomicBoolean();

    public DatabaseDownloader(
            final @NotNull ISPSenderConfiguration config, final @NotNull Path target) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(
                                Duration.ofSeconds(Math.max(1, config.getConnectTimeoutSeconds())))
                        .build();
    }

    /**
     * Downloads the database and installs it, unless another download is already running.
     *
     * @param database the database to install the result into
     * @return true if the database has been updated
     */
    public boolean download(final @NotNull IPInfoDatabase database) {
        Objects.requireNonNull(database, "database must not be null");
        if (!this.downloading.compareAndSet(false, true)) {
            LOGGER.warn("A database download is already running, skipping this one");
            return false;
        }
        try {
            return this.doDownload(database);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Database download interrupted");
            return false;
        } catch (final Exception e) {
            LOGGER.error("Failed to download the database ({})", e.getClass().getSimpleName());
            return false;
        } finally {
            deleteQuietly(this.target.resolveSibling(this.target.getFileName() + ".tmp"));
            this.downloading.set(false);
        }
    }

    private boolean doDownload(final @NotNull IPInfoDatabase database)
            throws IOException, InterruptedException {
        final String token = this.config.resolveToken();
        if (token == null) {
            LOGGER.error(
                    "No IPinfo token configured! Set 'token' in config.yml or the {} environment"
                        + " variable",
                    ISPSenderConfiguration.TOKEN_ENVIRONMENT_KEY);
            return false;
        }
        final String url =
                this.config
                        .getDatabaseUrl()
                        .replace(TOKEN_PATTERN, URLEncoder.encode(token, StandardCharsets.UTF_8));

        final Path directory = this.target.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        final Path temporary = this.target.resolveSibling(this.target.getFileName() + ".tmp");

        LOGGER.info("Downloading the IPinfo database from {}...", maskToken(url, token));
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(
                                Duration.ofSeconds(
                                        Math.max(1, this.config.getReadTimeoutSeconds())))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

        final long start = System.nanoTime();
        // HttpRequest.timeout stops at the response headers: a stalled body would block forever.
        final CompletableFuture<HttpResponse<Path>> pending =
                this.client.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofFile(
                                temporary,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING));
        final HttpResponse<Path> response;
        try {
            response =
                    pending.get(Math.max(1, this.config.getReadTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            pending.cancel(true);
            LOGGER.error(
                    "Database download did not finish in {}s",
                    this.config.getReadTimeoutSeconds());
            return false;
        } catch (final InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof IOException cause) throw cause;
            throw new IOException(e.getCause());
        }

        if (response.statusCode() != 200) {
            LOGGER.error("Database download failed with HTTP {}", response.statusCode());
            deleteQuietly(temporary);
            return false;
        }

        final Reader reader;
        try {
            reader = IPInfoDatabase.read(temporary);
        } catch (final IOException e) {
            LOGGER.error(
                    "The downloaded file is not a valid MMDB database, keeping the previous one",
                    e);
            deleteQuietly(temporary);
            return false;
        }

        final long size = Files.size(temporary);
        try {
            try {
                Files.move(
                        temporary,
                        this.target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, this.target, StandardCopyOption.REPLACE_EXISTING);
            }
            database.install(reader);
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
        LOGGER.info(
                "Database downloaded in {}s ({} MiB)",
                (System.nanoTime() - start) / 1_000_000_000L,
                size / (1024L * 1024L));
        return true;
    }

    private static @NotNull String maskToken(
            final @NotNull String url, final @NotNull String token) {
        return url.replace(URLEncoder.encode(token, StandardCharsets.UTF_8), "****")
                .replace(token, "****");
    }

    private static void deleteQuietly(final @NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException e) {
            LOGGER.warn("Failed to delete the temporary file '{}'", path, e);
        }
    }
}
