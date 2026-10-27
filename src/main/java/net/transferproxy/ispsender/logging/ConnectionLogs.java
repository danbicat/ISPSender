package net.transferproxy.ispsender.logging;

import com.google.gson.Gson;

import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.routing.Route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Bounded asynchronous disk logging: no filesystem work on player event threads. */
public final class ConnectionLogs implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionLogs.class);
    private static final Gson JSON = new Gson();

    public record Entry(
            String time,
            String player,
            String ip,
            String asn,
            String country,
            String server,
            String rule,
            String result) {}

    private final Path directory;
    private final Supplier<ZoneId> zone;
    private final IntSupplier retention;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private LocalDate cleaned;
    private final ExecutorService writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(10000),
                    r -> {
                        Thread t = new Thread(r, "ISPSender-Logs");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public ConnectionLogs(Path directory, Supplier<ZoneId> zone, IntSupplier retention) {
        this.directory = directory;
        this.zone = zone;
        this.retention = retention;
    }

    public void record(String player, InetAddress ip, IPInfoRecord info, Route route) {
        Entry entry =
                new Entry(
                        Instant.now().toString(),
                        player,
                        ip == null ? "" : ip.getHostAddress(),
                        info == null || info.asn() == null ? "" : info.asn(),
                        info == null || info.countryCode() == null ? "" : info.countryCode(),
                        route == null ? "" : route.server().toString(),
                        route == null ? "" : route.rule(),
                        route == null ? "kicked" : "transferred");
        synchronized (recent) {
            recent.addFirst(entry);
            while (recent.size() > 500) recent.removeLast();
        }
        LocalDate day = LocalDate.now(zone.get());
        try {
            writer.execute(() -> append(day, entry));
        } catch (RejectedExecutionException e) {
            LOGGER.warn("Connection log queue full or stopped; disk entry skipped");
        }
    }

    private void append(LocalDate day, Entry entry) {
        try {
            Files.createDirectories(directory);
            if (!day.equals(cleaned)) {
                cleanup(day);
                cleaned = day;
            }
            Files.writeString(
                    file(day),
                    JSON.toJson(entry) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("Cannot write connection log", e);
        }
    }

    private void cleanup(LocalDate day) throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl")) continue;
                try {
                    if (LocalDate.parse(name.substring(0, 10))
                            .isBefore(day.minusDays(retention.getAsInt() - 1)))
                        Files.deleteIfExists(path);
                } catch (DateTimeException ignored) {
                    /* Ignore unrelated malformed file names. */
                }
            }
        }
    }

    public List<Entry> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public List<Entry> read(LocalDate day, int offset, int limit) throws IOException {
        Path path = file(day);
        if (!Files.isRegularFile(path)) return List.of();
        try (var lines = Files.lines(path)) {
            return lines.map(ConnectionLogs::decode)
                    .filter(Objects::nonNull)
                    .skip(Math.max(0, offset))
                    .limit(Math.min(500, Math.max(1, limit)))
                    .toList();
        }
    }

    private static Entry decode(String line) {
        try {
            return JSON.fromJson(line, Entry.class);
        } catch (com.google.gson.JsonParseException e) {
            return null;
        }
    }

    private Path file(LocalDate day) {
        return directory.resolve(day + ".jsonl");
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
