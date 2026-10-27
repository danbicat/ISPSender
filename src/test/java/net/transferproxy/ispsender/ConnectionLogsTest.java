package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.logging.ConnectionLogs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;

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
}
