package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import net.transferproxy.api.util.ResourceUtil;
import net.transferproxy.ispsender.config.*;
import net.transferproxy.ispsender.database.*;
import net.transferproxy.ispsender.health.HealthMonitor;
import net.transferproxy.ispsender.logging.ConnectionLogs;
import net.transferproxy.ispsender.web.WebPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.Set;

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
