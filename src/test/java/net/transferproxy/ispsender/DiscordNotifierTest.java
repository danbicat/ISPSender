package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import net.transferproxy.ispsender.alert.DiscordNotifier;
import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.health.HealthMonitor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class DiscordNotifierTest {
    private static final String URL = "https://discord.com/api/webhooks/123456/abcDEF_-1";
    private static final Server KT = new Server("kt1.example", 25565);

    record Post(String url, String json) {}

    private static DiscordNotifier.Sender collect(LinkedBlockingQueue<Post> posts) {
        return (url, json) -> posts.add(new Post(url, json));
    }

    @Test
    void coalescesEventsIntoOneMessageWithoutMentions() throws Exception {
        var posts = new LinkedBlockingQueue<Post>();
        try (var notifier = new DiscordNotifier(() -> true, () -> URL, collect(posts))) {
            notifier.serverDown(KT, HealthMonitor.REFUSED);
            notifier.serverUp(KT);
            notifier.noRoute();
            Post post = posts.poll(5, TimeUnit.SECONDS);
            assertNotNull(post);
            assertEquals(URL, post.url());
            assertNull(posts.poll(300, TimeUnit.MILLISECONDS));
            var body = JsonParser.parseString(post.json()).getAsJsonObject();
            assertEquals("ISPSender", body.get("username").getAsString());
            assertEquals(
                    0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
            String[] lines = body.get("content").getAsString().split("\n");
            assertEquals(3, lines.length);
            assertEquals("🔴 서버 다운: kt1.example:25565 (연결 거부)", lines[0]);
            assertEquals("🟢 서버 복구: kt1.example:25565", lines[1]);
            assertTrue(lines[2].startsWith("⚠️ 사용 가능한 경로가 없어 플레이어 접속이 거절되고 있습니다"));
        }
    }

    @Test
    void longBatchesShowTwentyLinesAndCountTheRest() throws Exception {
        var posts = new LinkedBlockingQueue<Post>();
        try (var notifier = new DiscordNotifier(() -> true, () -> URL, collect(posts))) {
            for (int i = 0; i < 25; i++) {
                notifier.serverUp(new Server("host" + i + ".example", 25565));
            }
            Post post = posts.poll(5, TimeUnit.SECONDS);
            assertNotNull(post);
            String[] lines =
                    JsonParser.parseString(post.json())
                            .getAsJsonObject()
                            .get("content")
                            .getAsString()
                            .split("\n");
            assertEquals(21, lines.length);
            assertEquals("🟢 서버 복구: host0.example:25565", lines[0]);
            assertEquals("🟢 서버 복구: host19.example:25565", lines[19]);
            assertEquals("외 5건", lines[20]);
        }
    }

    @Test
    void noRouteIsThrottledToOncePerFiveMinutes() throws Exception {
        var posts = new LinkedBlockingQueue<Post>();
        var clock = new AtomicLong(1_000_000L);
        try (var notifier =
                new DiscordNotifier(() -> true, () -> URL, collect(posts), clock::get)) {
            notifier.noRoute();
            notifier.noRoute();
            notifier.noRoute();
            assertNotNull(posts.poll(5, TimeUnit.SECONDS));
            clock.addAndGet(4 * 60_000L);
            notifier.noRoute();
            assertNull(posts.poll(2_500, TimeUnit.MILLISECONDS));
            clock.addAndGet(60_000L);
            notifier.noRoute();
            assertNotNull(posts.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void skipsSilentlyWhenDisabledBlankOrNotADiscordUrl() throws Exception {
        var posts = new LinkedBlockingQueue<Post>();
        var enabled = new AtomicBoolean(false);
        var url = new AtomicReference<>(URL);
        try (var notifier = new DiscordNotifier(enabled::get, url::get, collect(posts))) {
            notifier.serverDown(KT, HealthMonitor.DNS);
            assertNull(posts.poll(2_500, TimeUnit.MILLISECONDS));
            enabled.set(true);
            url.set("");
            notifier.serverUp(KT);
            assertNull(posts.poll(2_500, TimeUnit.MILLISECONDS));
            url.set("https://example.com/not-a-discord-webhook");
            notifier.serverUp(KT);
            assertNull(posts.poll(2_500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void failedPostsDoNotStopLaterAlerts() throws Exception {
        var posts = new LinkedBlockingQueue<Post>();
        var calls = new AtomicInteger();
        DiscordNotifier.Sender flaky =
                (url, json) -> {
                    if (calls.incrementAndGet() == 1) throw new IOException("network down");
                    posts.add(new Post(url, json));
                };
        try (var notifier = new DiscordNotifier(() -> true, () -> URL, flaky)) {
            notifier.serverUp(KT);
            // The first batch is sent after the two-second window and fails.
            Thread.sleep(2_300);
            notifier.serverDown(KT, HealthMonitor.TIMEOUT);
            Post post = posts.poll(5, TimeUnit.SECONDS);
            assertNotNull(post);
            assertTrue(
                    JsonParser.parseString(post.json())
                            .getAsJsonObject()
                            .get("content")
                            .getAsString()
                            .contains("(시간 초과)"));
        }
    }

    @Test
    void longBatchesAreCutOnALineBreakBelowDiscordsLimit() throws Exception {
        var posts = new LinkedBlockingQueue<Post>();
        try (var notifier = new DiscordNotifier(() -> true, () -> URL, collect(posts))) {
            for (int i = 0; i < 20; i++) {
                notifier.serverDown(new Server(longHost(i), 25565), HealthMonitor.REFUSED);
            }
            Post post = posts.poll(5, TimeUnit.SECONDS);
            assertNotNull(post);
            String content =
                    JsonParser.parseString(post.json())
                            .getAsJsonObject()
                            .get("content")
                            .getAsString();
            assertTrue(content.length() <= 2000, "content has " + content.length() + " chars");
            String[] lines = content.split("\n");
            assertEquals("…", lines[lines.length - 1]);
            assertEquals("🔴 서버 다운: " + longHost(0) + ":25565 (연결 거부)", lines[0]);
            for (int i = 0; i < lines.length - 1; i++) {
                assertTrue(lines[i].endsWith(" (연결 거부)"), lines[i]);
            }
        }
    }

    /** A 253-character host name, the longest a DNS name may be, distinct for each index. */
    private static String longHost(int index) {
        String host =
                String.format("%02d", index)
                        + "a".repeat(61)
                        + "."
                        + "b".repeat(63)
                        + "."
                        + "c".repeat(63)
                        + "."
                        + "d".repeat(61);
        assertEquals(253, host.length());
        return host;
    }
}
