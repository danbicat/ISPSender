package net.transferproxy.ispsender.logging;

import com.google.gson.Gson;

import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.routing.Route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        return read(day, offset, limit, null);
    }

    /**
     * Reads one page of a day. A non-blank query keeps only the rows that contain it, ignoring case,
     * before the offset and limit are applied, so paging works over the matching rows.
     */
    public List<Entry> read(LocalDate day, int offset, int limit, String query)
            throws IOException {
        Path path = file(day);
        if (!Files.isRegularFile(path)) return List.of();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        try (var lines = Files.lines(path)) {
            return lines.map(ConnectionLogs::decode)
                    .filter(Objects::nonNull)
                    .filter(entry -> needle.isEmpty() || matchesQuery(entry, needle))
                    .skip(Math.max(0, offset))
                    .limit(Math.min(500, Math.max(1, limit)))
                    .toList();
        }
    }

    private static boolean matchesQuery(Entry entry, String needle) {
        return containsIgnoreCase(entry.player(), needle)
                || containsIgnoreCase(entry.ip(), needle)
                || containsIgnoreCase(entry.asn(), needle)
                || containsIgnoreCase(entry.country(), needle)
                || containsIgnoreCase(entry.server(), needle)
                || containsIgnoreCase(entry.rule(), needle)
                || containsIgnoreCase(entry.result(), needle);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Transferred and kicked counts for one key. */
    public record Bucket(String key, long transferred, long kicked) {}

    /** Totals of the range, one bucket per day (oldest first) and the top ten per dimension. */
    public record Stats(
            String from,
            String to,
            long transferred,
            long kicked,
            List<Bucket> days,
            List<Bucket> asn,
            List<Bucket> country,
            List<Bucket> server) {}

    /**
     * Totals for the days ending on {@code to}. The range is clamped to 1..31 days. Each day file is
     * streamed line by line, so memory stays bounded by the number of distinct keys.
     */
    public Stats stats(LocalDate to, int days) {
        int span = Math.max(1, Math.min(31, days));
        LocalDate from = to.minusDays(span - 1L);
        List<Bucket> daily = new ArrayList<>();
        Map<String, long[]> asn = new HashMap<>();
        Map<String, long[]> country = new HashMap<>();
        Map<String, long[]> server = new HashMap<>();
        long transferred = 0;
        long kicked = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long[] totals = new long[2];
            Path path = file(day);
            if (Files.isRegularFile(path)) {
                try (var lines = Files.lines(path)) {
                    lines.map(ConnectionLogs::decode)
                            .filter(Objects::nonNull)
                            .forEach(entry -> tally(entry, totals, asn, country, server));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            daily.add(new Bucket(day.toString(), totals[0], totals[1]));
            transferred += totals[0];
            kicked += totals[1];
        }
        return new Stats(
                from.toString(),
                to.toString(),
                transferred,
                kicked,
                List.copyOf(daily),
                top(asn),
                top(country),
                top(server));
    }

    private static void tally(
            Entry entry,
            long[] totals,
            Map<String, long[]> asn,
            Map<String, long[]> country,
            Map<String, long[]> server) {
        int slot;
        if ("transferred".equals(entry.result())) {
            slot = 0;
        } else if ("kicked".equals(entry.result())) {
            slot = 1;
        } else {
            return;
        }
        totals[slot]++;
        count(asn, orUnknown(entry.asn()), slot);
        count(country, orUnknown(entry.country()), slot);
        if (entry.server() != null && !entry.server().isBlank()) {
            count(server, entry.server(), slot);
        }
    }

    private static void count(Map<String, long[]> counts, String key, int slot) {
        counts.computeIfAbsent(key, unused -> new long[2])[slot]++;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static List<Bucket> top(Map<String, long[]> counts) {
        return counts.entrySet().stream()
                .map(e -> new Bucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(
                        Comparator.comparingLong((Bucket b) -> b.transferred() + b.kicked())
                                .reversed()
                                .thenComparing(Bucket::key))
                .limit(10)
                .toList();
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
