package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.database.DailyDatabaseUpdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

class DailyDatabaseUpdaterTest {
    @TempDir Path dir;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static class MutableClock extends Clock {
        Instant now;

        MutableClock(String time) {
            set(time);
        }

        void set(String time) {
            now = ZonedDateTime.parse(time + "+09:00[Asia/Seoul]").toInstant();
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        public Instant instant() {
            return now;
        }
    }

    @Test
    void downloadsOnceAndCatchesUpAtTwoAmAfterDowntime() throws Exception {
        Path path = dir.resolve("db.mmdb");
        var clock = new MutableClock("2026-10-01T00:00:00");
        var calls = new AtomicInteger();
        var action =
                (java.util.function.BooleanSupplier)
                        () -> {
                            calls.incrementAndGet();
                            try {
                                Files.writeString(path, "db");
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        };
        try (var first = new DailyDatabaseUpdater(path, clock, () -> SEOUL, action)) {
            assertTrue(first.updateIfDue());
            assertFalse(first.updateIfDue());
            clock.set("2026-10-01T23:00:00");
            assertFalse(first.updateIfDue());
        }
        try (var restart = new DailyDatabaseUpdater(path, clock, () -> SEOUL, action)) {
            assertFalse(restart.updateIfDue());
        }
        clock.set("2026-10-02T02:00:00");
        try (var restart = new DailyDatabaseUpdater(path, clock, () -> SEOUL, action)) {
            assertTrue(restart.updateIfDue());
            assertFalse(restart.updateIfDue());
            clock.set("2026-10-03T00:00:00");
            assertTrue(restart.updateIfDue());
        }
        assertEquals(3, calls.get());
        assertEquals("2026-10-03", Files.readString(dir.resolve("db.mmdb.download-date")));
    }

    @Test
    void failedDownloadsRetryAndMissingFileOverridesSuccessDate() throws Exception {
        Path path = dir.resolve("db.mmdb");
        Files.writeString(path, "old");
        var clock = new MutableClock("2026-10-02T00:00:00");
        try (var update = new DailyDatabaseUpdater(path, clock, () -> SEOUL, () -> false)) {
            assertFalse(update.updateIfDue());
            assertTrue(update.isDue());
            assertNull(update.lastSuccess());
        }
        Files.writeString(dir.resolve("db.mmdb.download-date"), "2026-10-02");
        Files.delete(path);
        try (var update = new DailyDatabaseUpdater(path, clock, () -> SEOUL, () -> false)) {
            assertTrue(update.isDue());
        }
    }

    @Test
    void corruptedCacheCanBeInvalidatedEvenWithTodaysStamp() throws Exception {
        Path path = dir.resolve("db.mmdb");
        Files.writeString(path, "corrupt");
        Files.writeString(dir.resolve("db.mmdb.download-date"), "2026-10-02");
        var clock = new MutableClock("2026-10-02T12:00:00");
        try (var update = new DailyDatabaseUpdater(path, clock, () -> SEOUL, () -> true)) {
            assertFalse(update.isDue());
            update.invalidate();
            assertTrue(update.updateIfDue());
        }
    }

    @Test
    void schedulesCalendarMidnightAcrossDaylightSaving() throws Exception {
        Path path = dir.resolve("db.mmdb");
        Files.writeString(path, "db");
        Files.writeString(dir.resolve("db.mmdb.download-date"), "2026-11-01");
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T04:00:00Z"), ZoneOffset.UTC);
        try (var update =
                new DailyDatabaseUpdater(
                        path,
                        clock,
                        () -> ZoneId.of("America/New_York"),
                        () -> fail("already current"))) {
            update.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (update.nextUpdate() == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(Instant.parse("2026-11-02T05:00:00Z"), update.nextUpdate());
        }
    }
}
