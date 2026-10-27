package net.transferproxy.ispsender.database;

import com.maxmind.db.Metadata;
import com.maxmind.db.Reader;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Holds the currently loaded IPinfo database.
 *
 * <p>The reader is always created from an {@link InputStream}, so the database is fully loaded in
 * memory and the file on disk stays free to be replaced by the downloader. Swapping the reader is
 * atomic for the lookup threads.
 */
public final class IPInfoDatabase implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IPInfoDatabase.class);

    private volatile Reader reader;

    /**
     * Loads a database file in memory and installs it as the active one.
     *
     * @param path the database file
     * @return true if the database has been loaded
     */
    public boolean loadFrom(final @NotNull Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            this.install(read(path));
            return true;
        } catch (final IOException e) {
            LOGGER.error("Failed to read the database '{}'", path, e);
            return false;
        }
    }

    /**
     * Reads a database file in memory without installing it, to validate a freshly downloaded file.
     *
     * @param path the database file
     * @return the opened reader
     * @throws IOException if the file is missing or is not a valid MMDB file
     */
    public static @NotNull Reader read(final @NotNull Path path) throws IOException {
        try (final InputStream stream = new BufferedInputStream(Files.newInputStream(path))) {
            return new Reader(stream);
        }
    }

    /**
     * Installs a reader as the active one and closes the previous one.
     *
     * @param newReader the reader to install
     */
    public synchronized void install(final @NotNull Reader newReader) {
        Objects.requireNonNull(newReader, "newReader must not be null");
        final Reader previous = this.reader;
        this.reader = newReader;
        close(previous);

        final Metadata metadata = newReader.getMetadata();
        LOGGER.info(
                "Loaded database '{}' built on {} ({} nodes)",
                metadata.databaseType(),
                metadata.buildTime(),
                metadata.nodeCount());
    }

    /**
     * Looks up an address in the active database.
     *
     * @param address the address to look up
     * @return the record, or null when the database is not loaded or the address is unknown
     */
    public synchronized @Nullable IPInfoRecord lookup(final @Nullable InetAddress address) {
        final Reader currentReader = this.reader;
        if (currentReader == null || address == null) {
            return null;
        }
        try {
            return currentReader.get(address, IPInfoRecord.class);
        } catch (final Exception e) {
            LOGGER.warn("Failed to look up '{}'", address.getHostAddress(), e);
            return null;
        }
    }

    public boolean isLoaded() {
        return this.reader != null;
    }

    @Override
    public synchronized void close() {
        final Reader previous = this.reader;
        this.reader = null;
        close(previous);
    }

    private static void close(final @Nullable Reader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (final IOException e) {
            LOGGER.warn("Failed to close a database reader", e);
        }
    }
}
