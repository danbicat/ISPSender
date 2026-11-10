package net.transferproxy.ispsender.alert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.health.HealthMonitor;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Posts server and routing alerts to a Discord webhook.
 *
 * <p>Events are queued without blocking the caller, coalesced for two seconds and sent as one
 * message by a single daemon thread. The URL is never logged, and no message mentions anyone.
 */
public final class DiscordNotifier implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordNotifier.class);
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long COALESCE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long NO_ROUTE_THROTTLE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int QUEUE_CAPACITY = 1024;
    private static final int MAX_LINES = 20;
    /** Discord refuses a longer message, and then the whole batch is lost. */
    private static final int MAX_CONTENT = 2000;
    private static final String CUT_MARK = "\n…";
    private static final String NO_ROUTE_LINE =
            "⚠️ 사용 가능한 경로가 없어 플레이어 접속이 거절되고 있습니다";

    /** Posts one JSON body. Implementations must never put the URL into an exception message. */
    public interface Sender {
        void post(String url, String json) throws Exception;
    }

    /** A non-2xx answer from Discord. It carries the status code only. */
    static final class RejectedException extends Exception {
        private final int status;

        RejectedException(final int status) {
            super("Discord answered HTTP " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private final Supplier<Boolean> enabled;
    private final Supplier<String> url;
    private final Sender sender;
    private final LongSupplier clock;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong lastNoRoute = new AtomicLong(-1L);
    private final Thread worker;
    private volatile boolean closed;
    private boolean invalidWarned;

    public DiscordNotifier(
            final Supplier<Boolean> enabled, final Supplier<String> url, final Sender sender) {
        this(enabled, url, sender, System::currentTimeMillis);
    }

    /** Uses the Discord HTTP API: no redirects, 10 second timeouts. */
    public DiscordNotifier(final Supplier<Boolean> enabled, final Supplier<String> url) {
        this(enabled, url, httpSender());
    }

    public DiscordNotifier(
            final Supplier<Boolean> enabled,
            final Supplier<String> url,
            final Sender sender,
            final LongSupplier clock) {
        this.enabled = Objects.requireNonNull(enabled, "enabled must not be null");
        this.url = Objects.requireNonNull(url, "url must not be null");
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.worker = new Thread(this::run, "ISPSender-Alerts");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Queues a server-down alert. Never blocks. */
    public void serverDown(final Server server, final @Nullable String reason) {
        offer(
                "🔴 서버 다운: "
                        + server
                        + (reason == null ? "" : " (" + reasonText(reason) + ")"));
    }

    /** Queues a server-recovered alert. Never blocks. */
    public void serverUp(final Server server) {
        offer("🟢 서버 복구: " + server);
    }

    /** Queues a no-route alert, at most once per five minutes. Never blocks. */
    public void noRoute() {
        if (closed || !Boolean.TRUE.equals(enabled.get())) return;
        long now = clock.getAsLong();
        long last = lastNoRoute.get();
        if (last >= 0 && now - last < NO_ROUTE_THROTTLE_MILLIS) return;
        if (lastNoRoute.compareAndSet(last, now)) offer(NO_ROUTE_LINE);
    }

    private void offer(final String line) {
        if (closed || !Boolean.TRUE.equals(enabled.get())) return;
        // A full queue drops the event: alerts must never hold up players or probes.
        queue.offer(line);
    }

    private void run() {
        try {
            while (!closed) {
                List<String> batch = new ArrayList<>();
                batch.add(queue.take());
                long deadline = System.nanoTime() + COALESCE_NANOS;
                for (long wait = deadline - System.nanoTime();
                        wait > 0;
                        wait = deadline - System.nanoTime()) {
                    String next = queue.poll(wait, TimeUnit.NANOSECONDS);
                    if (next == null) break;
                    batch.add(next);
                }
                post(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void post(final List<String> batch) {
        if (!Boolean.TRUE.equals(enabled.get())) return;
        String target = url.get();
        if (target == null || target.isBlank()) return;
        String webhook = target.trim();
        if (!ISPSenderConfiguration.isDiscordWebhook(webhook)) {
            if (!invalidWarned) {
                LOGGER.warn("discord-webhook-url is not a Discord webhook URL, alerts are skipped");
                invalidWarned = true;
            }
            return;
        }
        invalidWarned = false;
        String json = JSON.toJson(body(content(batch)));
        try {
            sender.post(webhook, json);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedException e) {
            LOGGER.warn("Discord rejected an alert (HTTP {})", e.status());
        } catch (Exception e) {
            LOGGER.warn("Discord alert failed ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * At most {@value #MAX_LINES} lines, then the number of the rest. Longer text is cut to
     * {@value #MAX_CONTENT} characters.
     */
    static String content(final List<String> lines) {
        StringJoiner joiner = new StringJoiner("\n");
        for (String line : lines.subList(0, Math.min(MAX_LINES, lines.size()))) {
            joiner.add(line);
        }
        if (lines.size() > MAX_LINES) {
            joiner.add("외 " + (lines.size() - MAX_LINES) + "건");
        }
        return truncate(joiner.toString());
    }

    /** Cuts before a line break, so no line is split, and marks the cut. */
    private static String truncate(final String text) {
        if (text.length() <= MAX_CONTENT) return text;
        int end = MAX_CONTENT - CUT_MARK.length();
        int lineBreak = text.lastIndexOf('\n', end);
        if (lineBreak > 0) end = lineBreak;
        else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + CUT_MARK;
    }

    private static Map<String, Object> body(final String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "ISPSender");
        body.put("allowed_mentions", Map.of("parse", List.of()));
        body.put("content", content);
        return body;
    }

    private static String reasonText(final String reason) {
        return switch (reason) {
            case HealthMonitor.DNS -> "DNS 조회 실패";
            case HealthMonitor.REFUSED -> "연결 거부";
            case HealthMonitor.TIMEOUT -> "시간 초과";
            case HealthMonitor.UNREACHABLE -> "네트워크 도달 불가";
            case HealthMonitor.BAD_RESPONSE -> "Minecraft 응답 없음";
            default -> reason;
        };
    }

    private static Sender httpSender() {
        HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
        return (url, json) -> {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();
            int status =
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status > 299) throw new RejectedException(status);
        };
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }
}
