package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import net.transferproxy.ispsender.logging.ConnectionLogs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.List;

class ConnectionLogsTest {
    @TempDir Path dir;

    @Test
    void retentionPaginationAndCrashTruncatedRecordsAreHandled() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path expired = dir.resolve(today.minusDays(30) + ".jsonl");
        Path retained = dir.resolve(today.minusDays(29) + ".jsonl");
        Files.writeString(expired, "old");
        Files.writeString(retained, "retained");
        Files.writeString(dir.resolve("2026-99-99.jsonl"), "unrelated");
        var logs = new ConnectionLogs(dir, () -> ZoneOffset.UTC, () -> 30);
        for (int i = 0; i < 105; i++) logs.record("Player" + i, null, null, null);
        logs.close();
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(retained));
        Files.writeString(dir.resolve(today + ".jsonl"), "{truncated", StandardOpenOption.APPEND);
        assertEquals(100, logs.read(today, 0, 100).size());
        assertEquals(5, logs.read(today, 100, 100).size());
        assertEquals("Player100", logs.read(today, 100, 100).get(0).player());
        try (var restarted = new ConnectionLogs(dir, () -> ZoneOffset.UTC, () -> 30)) {
            assertEquals(105, restarted.read(today, 0, 500).size());
        }
    }

    static String line(String player, String asn, String country, String server, String result) {
        var entry =
                new ConnectionLogs.Entry(
                        "2026-11-09T00:00:00Z",
                        player,
                        "203.0.113.7",
                        asn,
                        country,
                        server,
                        "default",
                        result);
        return new Gson().toJson(entry) + "\n";
    }

    @Test
    void searchMatchesBeforePagingSoPagesCrossTheMatchingRows() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var file = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            file.append(
                    line(
                            "Player" + i,
                            "AS" + (i % 2),
                            i % 3 == 0 ? "KR" : "JP",
                            "srv" + i,
                            "transferred"));
        }
        Files.writeString(dir.resolve(today + ".jsonl"), file.toString());
        try (var logs = new ConnectionLogs(dir, () -> ZoneOffset.UTC, () -> 30)) {
            // Country KR is every third row (Player0, Player3, ...), so 50 rows match.
            assertEquals(50, logs.read(today, 0, 500, " kr ").size());
            var page = logs.read(today, 30, 30, "KR");
            assertEquals(20, page.size());
            assertEquals("Player90", page.get(0).player());
            assertEquals(150, logs.read(today, 0, 500, "   ").size());
            assertEquals(0, logs.read(today, 0, 500, "no-such-text").size());
        }
    }

    @Test
    void statsCountEveryDayAndRankTheTopKeysPerDimension() throws Exception {
        LocalDate to = LocalDate.of(2026, 11, 9);
        Files.writeString(
                dir.resolve(to.minusDays(2) + ".jsonl"),
                line("A", "AS4766", "KR", "a:25565", "transferred")
                        + line("B", "AS4766", "KR", "", "kicked")
                        + line("C", "", "", "b:25565", "transferred")
                        + "{truncated\n");
        Files.writeString(
                dir.resolve(to + ".jsonl"), line("D", "AS9318", "JP", "a:25565", "kicked"));
        try (var logs = new ConnectionLogs(dir, () -> ZoneOffset.UTC, () -> 30)) {
            var stats = logs.stats(to, 3);
            assertEquals("2026-11-07", stats.from());
            assertEquals("2026-11-09", stats.to());
            assertEquals(2, stats.transferred());
            assertEquals(2, stats.kicked());
            assertEquals(
                    List.of(
                            new ConnectionLogs.Bucket("2026-11-07", 2, 1),
                            new ConnectionLogs.Bucket("2026-11-08", 0, 0),
                            new ConnectionLogs.Bucket("2026-11-09", 0, 1)),
                    stats.days());
            assertEquals(
                    List.of(
                            new ConnectionLogs.Bucket("AS4766", 1, 1),
                            new ConnectionLogs.Bucket("AS9318", 0, 1),
                            new ConnectionLogs.Bucket("unknown", 1, 0)),
                    stats.asn());
            assertEquals(
                    List.of(
                            new ConnectionLogs.Bucket("KR", 1, 1),
                            new ConnectionLogs.Bucket("JP", 0, 1),
                            new ConnectionLogs.Bucket("unknown", 1, 0)),
                    stats.country());
            // The kicked row without a server is not counted in the server dimension.
            assertEquals(
                    List.of(
                            new ConnectionLogs.Bucket("a:25565", 1, 1),
                            new ConnectionLogs.Bucket("b:25565", 1, 0)),
                    stats.server());
        }
    }

    @Test
    void statsClampsTheRangeAndKeepsTenRowsPerDimension() throws Exception {
        LocalDate to = LocalDate.of(2026, 11, 9);
        var file = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            file.append(line("P" + i, "AS" + i, "C" + i, "s" + i + ":25565", "transferred"));
        }
        Files.writeString(dir.resolve(to + ".jsonl"), file.toString());
        try (var logs = new ConnectionLogs(dir, () -> ZoneOffset.UTC, () -> 30)) {
            var stats = logs.stats(to, 1);
            assertEquals(1, stats.days().size());
            assertEquals(12, stats.transferred());
            assertEquals(10, stats.asn().size());
            assertEquals(10, stats.country().size());
            assertEquals(10, stats.server().size());
            assertEquals(1, logs.stats(to, 0).days().size());
            assertEquals(31, logs.stats(to, 99).days().size());
        }
    }
}
