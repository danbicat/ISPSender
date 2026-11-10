package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import net.transferproxy.api.util.ResourceUtil;
import net.transferproxy.ispsender.config.*;
import net.transferproxy.ispsender.database.*;
import net.transferproxy.ispsender.dns.SrvResolver;
import net.transferproxy.ispsender.health.HealthMonitor;
import net.transferproxy.ispsender.logging.ConnectionLogs;
import net.transferproxy.ispsender.web.WebPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

class WebPanelTest {
    @TempDir Path dir;

    @Test
    void tailnetRangesExcludePublicAndLanAndBindIsRestricted() throws Exception {
        for (String ip : new String[] {"100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1"})
            assertTrue(WebPanel.isTailnet(InetAddress.getByName(ip)));
        for (String ip :
                new String[] {
                    "100.63.255.255", "100.128.0.1", "8.8.8.8", "192.168.1.2", "fd7a:115c:a1e1::1"
                }) assertFalse(WebPanel.isTailnet(InetAddress.getByName(ip)));
        WebPanel.validateBind("127.0.0.1");
        for (String bind : new String[] {"0.0.0.0", "::", "192.168.1.2", "8.8.8.8", "example.com"})
            assertThrows(IllegalArgumentException.class, () -> WebPanel.validateBind(bind));
    }

    @Test
    void passwordCsrfSecretRedactionPersistenceLogsAndLogoutWorkOverHttp() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var c = new ISPSenderConfiguration();
        c.setWebPort(port);
        c.setWebEnabled(true);
        c.setWebPassword("test-password-12345");
        c.setToken("never-expose-token");
        var store = new ConfigurationStore(dir.resolve("config.yml"), c);
        try (var db = new IPInfoDatabase();
                var health = new HealthMonitor(Set::of, () -> 200);
                var logs =
                        new ConnectionLogs(
                                dir.resolve("logs"), () -> ZoneId.of("Asia/Seoul"), () -> 30);
                var updater =
                        new DailyDatabaseUpdater(
                                dir.resolve("db"),
                                Clock.systemUTC(),
                                () -> ZoneOffset.UTC,
                                () -> false);
                var panel = new WebPanel(store, health, db, updater, logs, () -> {})) {
            panel.start();
            String base = "http://127.0.0.1:" + port;
            CookieManager cookies = new CookieManager();
            HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
            assertEquals(200, send(client, base, "/", "GET", null, null).statusCode());
            assertEquals(401, send(client, base, "/api/state", "GET", null, null).statusCode());
            assertEquals(
                    401,
                    send(client, base, "/api/login", "POST", "{\"password\":\"wrong\"}", null)
                            .statusCode());
            var login =
                    send(
                            client,
                            base,
                            "/api/login",
                            "POST",
                            "{\"password\":\"test-password-12345\"}",
                            null);
            assertEquals(200, login.statusCode());
            assertTrue(
                    login.headers()
                            .firstValue("set-cookie")
                            .orElseThrow()
                            .contains("HttpOnly; SameSite=Strict"));
            String csrf =
                    JsonParser.parseString(login.body())
                            .getAsJsonObject()
                            .get("csrf")
                            .getAsString();
            var state = send(client, base, "/api/state", "GET", null, null);
            assertEquals(200, state.statusCode());
            assertFalse(state.body().contains("never-expose-token"));
            assertFalse(state.body().contains("test-password-12345"));
            assertEquals(
                    403,
                    send(client, base, "/api/config", "PUT", "{\"fallback-enabled\":false}", null)
                            .statusCode());
            var rejected =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/api/config"))
                                    .header("Content-Type", "application/json")
                                    .header("Origin", "https://evil.example")
                                    .header("X-CSRF-Token", csrf)
                                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rejected.statusCode());
            var save =
                    send(
                            client,
                            base,
                            "/api/config",
                            "PUT",
                            "{\"default-server\":[\"first.example\",\"second.example\"],\"fallback-enabled\":false}",
                            csrf);
            assertEquals(200, save.statusCode(), save.body());
            assertFalse(
                    ResourceUtil.readYaml(dir.resolve("config.yml"), ISPSenderConfiguration.class)
                            .isFallbackEnabled());
            assertEquals(
                    2, ((java.util.List<?>) store.current().config().getDefaultServer()).size());
            assertEquals(
                    400,
                    send(client, base, "/api/config", "PUT", "{\"web-bind\":\"0.0.0.0\"}", csrf)
                            .statusCode());
            assertEquals("127.0.0.1", store.current().config().getWebBind());
            // Valid range, but saving it would lock the panel out after the restart.
            assertEquals(
                    400,
                    send(client, base, "/api/config", "PUT", "{\"web-bind\":\"100.100.100.100\"}", csrf)
                            .statusCode());
            assertEquals("127.0.0.1", store.current().config().getWebBind());
            logs.record("<script>alert(1)</script>", InetAddress.getLoopbackAddress(), null, null);
            logs.close();
            String day = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
            var history = send(client, base, "/api/logs?date=" + day, "GET", null, null);
            assertEquals(200, history.statusCode());
            assertTrue(history.body().contains("kicked"));
            assertEquals(
                    400,
                    send(client, base, "/api/logs?date=invalid", "GET", null, null).statusCode());
            assertEquals(200, send(client, base, "/api/logout", "POST", "{}", csrf).statusCode());
            assertEquals(401, send(client, base, "/api/state", "GET", null, null).statusCode());
            for (int i = 0; i < 10; i++)
                assertEquals(
                        401,
                        send(client, base, "/api/login", "POST", "{\"password\":\"wrong\"}", null)
                                .statusCode());
            assertEquals(
                    429,
                    send(client, base, "/api/login", "POST", "{\"password\":\"wrong\"}", null)
                            .statusCode());
        }
    }

    @Test
    void simulatorAddressChecksAndStatisticsAnswerGoodAndBadRequests() throws Exception {
        ServerSocket defaultOne = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ServerSocket defaultTwo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ServerSocket srvTarget = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        HealthMonitorTest.serveForever(srvTarget, HealthMonitorTest.statusReply());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        try {
            var c = new ISPSenderConfiguration();
            c.setWebPort(port);
            c.setWebEnabled(true);
            c.setWebPassword("test-password-12345");
            c.setHealthCheckTimeoutMillis(500);
            c.setDefaultServer(
                    List.of(
                            "127.0.0.1:" + defaultOne.getLocalPort(),
                            "127.0.0.1:" + defaultTwo.getLocalPort()));
            var store = new ConfigurationStore(dir.resolve("config.yml"), c);
            var resolver =
                    new SrvResolver(
                            name ->
                                    name.equals("_minecraft._tcp.srv-only.test")
                                            ? List.of(
                                                    new SrvResolver.Record(
                                                            0, 0, srvTarget.getLocalPort(), "localhost."))
                                            : List.of(),
                            () -> 0L);
            try (var db = new IPInfoDatabase();
                    var health =
                            new HealthMonitor(
                                    () -> store.current().routing().allServers(),
                                    () -> 500,
                                    () -> HealthMonitor.Mode.TCP,
                                    () -> 1,
                                    resolver,
                                    HealthMonitor.Listener.NONE);
                    var logs =
                            new ConnectionLogs(dir.resolve("logs"), () -> ZoneOffset.UTC, () -> 30);
                    var updater =
                            new DailyDatabaseUpdater(
                                    dir.resolve("db"),
                                    Clock.systemUTC(),
                                    () -> ZoneOffset.UTC,
                                    () -> false);
                    var panel = new WebPanel(store, health, db, updater, logs, () -> {})) {
                panel.start();
                HealthMonitorTest.refreshUntil(
                        health, () -> health.snapshot().stream().allMatch(HealthMonitor.Status::alive));
                String base = "http://127.0.0.1:" + port;
                HttpClient client =
                        HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
                String csrf =
                        json(
                                        send(
                                                client,
                                                base,
                                                "/api/login",
                                                "POST",
                                                "{\"password\":\"test-password-12345\"}",
                                                null))
                                .get("csrf")
                                .getAsString();

                // Simulator: literal addresses only; the default pool answers with its first live server.
                String simulate = "/api/simulate";
                var simulated = send(client, base, simulate, "POST", "{\"ip\":\"1.2.3.4\"}", csrf);
                assertEquals(200, simulated.statusCode(), simulated.body());
                JsonObject result = json(simulated);
                assertEquals("1.2.3.4", result.get("ip").getAsString());
                assertFalse(result.get("found").getAsBoolean());
                JsonArray steps = result.getAsJsonArray("steps");
                assertEquals(1, steps.size());
                JsonObject step = steps.get(0).getAsJsonObject();
                assertEquals("default", step.get("rule").getAsString());
                assertTrue(step.get("selected").getAsBoolean());
                assertEquals(2, step.getAsJsonArray("available").size());
                JsonObject route = result.getAsJsonObject("route");
                assertEquals("default", route.get("rule").getAsString());
                assertEquals(
                        "127.0.0.1:" + defaultOne.getLocalPort(), route.get("server").getAsString());
                assertEquals(route.get("server").getAsString(), route.get("target").getAsString());
                assertEquals(
                        "2001:db8:0:0:0:0:0:1",
                        json(
                                        send(
                                                client,
                                                base,
                                                simulate,
                                                "POST",
                                                "{\"ip\":\"2001:db8::1\"}",
                                                csrf))
                                .get("ip")
                                .getAsString());
                for (String bad :
                        List.of("{\"ip\":\"example.com\"}", "{\"ip\":\"999.1.1.1\"}", "{}")) {
                    var rejected = send(client, base, simulate, "POST", bad, csrf);
                    assertEquals(400, rejected.statusCode(), bad);
                    assertEquals(
                            "올바른 IP 주소를 입력하세요.", json(rejected).get("error").getAsString());
                }

                // Address check: SRV-only domain, then a plain address that accepts TCP but not ping.
                String check = "/api/check-address";
                var srvCheck =
                        send(client, base, check, "POST", "{\"address\":\"srv-only.test\"}", csrf);
                assertEquals(200, srvCheck.statusCode(), srvCheck.body());
                JsonObject srvResult = json(srvCheck);
                assertTrue(srvResult.get("srvEligible").getAsBoolean());
                assertTrue(srvResult.get("usesSrv").getAsBoolean());
                assertEquals(
                        "localhost",
                        srvResult
                                .getAsJsonArray("srv")
                                .get(0)
                                .getAsJsonObject()
                                .get("target")
                                .getAsString());
                assertEquals(
                        "localhost:" + srvTarget.getLocalPort(),
                        srvResult.get("target").getAsString());
                assertTrue(srvResult.get("reachable").getAsBoolean());
                assertTrue(srvResult.get("minecraft").getAsBoolean());
                assertFalse(srvResult.has("reason"));
                JsonObject plain =
                        json(
                                send(
                                        client,
                                        base,
                                        check,
                                        "POST",
                                        "{\"address\":\"127.0.0.1:" + defaultOne.getLocalPort() + "\"}",
                                        csrf));
                assertTrue(plain.get("reachable").getAsBoolean());
                assertFalse(plain.get("minecraft").getAsBoolean());
                assertEquals("TIMEOUT", plain.get("reason").getAsString());
                assertFalse(plain.get("usesSrv").getAsBoolean());
                JsonObject refused =
                        json(
                                send(
                                        client,
                                        base,
                                        check,
                                        "POST",
                                        "{\"address\":\"127.0.0.1:" + closedPort + "\"}",
                                        csrf));
                assertFalse(refused.get("reachable").getAsBoolean());
                assertEquals("REFUSED", refused.get("reason").getAsString());
                var malformed =
                        send(client, base, check, "POST", "{\"address\":\"bad address!\"}", csrf);
                assertEquals(400, malformed.statusCode());
                assertEquals(
                        "주소 형식이 올바르지 않습니다.", json(malformed).get("error").getAsString());
                // Brackets are for IPv6 literals only: a name is refused before any DNS lookup.
                var bracketedName =
                        send(
                                client,
                                base,
                                check,
                                "POST",
                                "{\"address\":\"[some-name.example]\"}",
                                csrf);
                assertEquals(400, bracketedName.statusCode());
                assertEquals(
                        "주소 형식이 올바르지 않습니다.",
                        json(bracketedName).get("error").getAsString());

                // Statistics: range checks, then one transferred and one kicked row for today.
                assertEquals(
                        3,
                        json(send(client, base, "/api/stats?days=3", "GET", null, null))
                                .getAsJsonArray("days")
                                .size());
                assertEquals(
                        7,
                        json(send(client, base, "/api/stats", "GET", null, null))
                                .getAsJsonArray("days")
                                .size());
                for (String query : List.of("days=0", "days=32", "days=abc")) {
                    assertEquals(
                            400,
                            send(client, base, "/api/stats?" + query, "GET", null, null)
                                    .statusCode(),
                            query);
                }
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                Files.createDirectories(dir.resolve("logs"));
                Files.writeString(
                        dir.resolve("logs").resolve(today + ".jsonl"),
                        logLine("Alpha", "transferred") + logLine("Bravo", "kicked"));
                JsonObject day = json(send(client, base, "/api/stats?days=1", "GET", null, null));
                assertEquals(1, day.get("transferred").getAsLong());
                assertEquals(1, day.get("kicked").getAsLong());

                // Search runs on the server across the whole day.
                var searched =
                        JsonParser.parseString(
                                        send(
                                                        client,
                                                        base,
                                                        "/api/logs?date=" + today + "&q=BRAVO",
                                                        "GET",
                                                        null,
                                                        null)
                                                .body())
                                .getAsJsonArray();
                assertEquals(1, searched.size());
                assertEquals(
                        "Bravo", searched.get(0).getAsJsonObject().get("player").getAsString());
                assertEquals(
                        400,
                        send(
                                        client,
                                        base,
                                        "/api/logs?date=" + today + "&q=" + "a".repeat(101),
                                        "GET",
                                        null,
                                        null)
                                .statusCode());

                JsonObject state = json(send(client, base, "/api/state", "GET", null, null));
                assertEquals(2, state.get("healthFailureThreshold").getAsInt());
                assertTrue(state.has("discordConfigured"));

                // Webhook: an invalid URL is refused without echoing it; a blank one keeps the saved value.
                var badWebhook =
                        send(
                                client,
                                base,
                                "/api/config",
                                "PUT",
                                "{\"discord-webhook-url\":\"https://evil.example/hook\"}",
                                csrf);
                assertEquals(400, badWebhook.statusCode());
                assertFalse(badWebhook.body().contains("evil.example"));
                assertEquals(
                        200,
                        send(
                                        client,
                                        base,
                                        "/api/config",
                                        "PUT",
                                        "{\"discord-webhook-url\":\"\"}",
                                        csrf)
                                .statusCode());
                assertEquals(
                        400,
                        send(
                                        client,
                                        base,
                                        "/api/config",
                                        "PUT",
                                        "{\"health-failure-threshold\":11}",
                                        csrf)
                                .statusCode());
            }
        } finally {
            defaultOne.close();
            defaultTwo.close();
            srvTarget.close();
        }
    }

    @Test
    void addressChecksRunTwoAtATimeAndAnswerTheThirdWith429() throws Exception {
        // Accepts TCP through the backlog but never answers, so each check waits for its timeout.
        ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            var c = new ISPSenderConfiguration();
            c.setWebPort(port);
            c.setWebEnabled(true);
            c.setWebPassword("test-password-12345");
            c.setHealthCheckTimeoutMillis(1000);
            var store = new ConfigurationStore(dir.resolve("config.yml"), c);
            try (var db = new IPInfoDatabase();
                    var health = new HealthMonitor(Set::of, () -> 1000);
                    var logs =
                            new ConnectionLogs(dir.resolve("logs"), () -> ZoneOffset.UTC, () -> 30);
                    var updater =
                            new DailyDatabaseUpdater(
                                    dir.resolve("db"),
                                    Clock.systemUTC(),
                                    () -> ZoneOffset.UTC,
                                    () -> false);
                    var panel = new WebPanel(store, health, db, updater, logs, () -> {})) {
                panel.start();
                String base = "http://127.0.0.1:" + port;
                HttpClient client =
                        HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
                String csrf =
                        json(
                                        send(
                                                client,
                                                base,
                                                "/api/login",
                                                "POST",
                                                "{\"password\":\"test-password-12345\"}",
                                                null))
                                .get("csrf")
                                .getAsString();
                String body = "{\"address\":\"127.0.0.1:" + silent.getLocalPort() + "\"}";
                List<HttpResponse<String>> slow = new CopyOnWriteArrayList<>();
                Thread[] threads = new Thread[2];
                for (int i = 0; i < threads.length; i++) {
                    threads[i] =
                            new Thread(
                                    () -> {
                                        try {
                                            slow.add(
                                                    send(
                                                            client,
                                                            base,
                                                            "/api/check-address",
                                                            "POST",
                                                            body,
                                                            csrf));
                                        } catch (Exception ignored) {
                                            /* The assertions below report missing answers. */
                                        }
                                    });
                    threads[i].start();
                }
                Thread.sleep(300);
                var third = send(client, base, "/api/check-address", "POST", body, csrf);
                assertEquals(429, third.statusCode(), third.body());
                assertEquals(
                        "다른 확인이 진행 중입니다. 잠시 후 다시 시도하세요.",
                        json(third).get("error").getAsString());
                for (Thread thread : threads) thread.join(10_000);
                assertEquals(2, slow.size());
                for (var response : slow) {
                    assertEquals(200, response.statusCode(), response.body());
                }
            }
        } finally {
            silent.close();
        }
    }

    static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    static String logLine(String player, String result) {
        var entry =
                new ConnectionLogs.Entry(
                        Instant.now().toString(),
                        player,
                        "203.0.113.7",
                        "AS4766",
                        "KR",
                        result.equals("kicked") ? "" : "127.0.0.1:1",
                        "default",
                        result);
        return new Gson().toJson(entry) + "\n";
    }

    static HttpResponse<String> send(
            HttpClient client, String base, String path, String method, String body, String csrf)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
