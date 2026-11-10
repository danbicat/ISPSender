package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.api.util.ResourceUtil;
import net.transferproxy.ispsender.config.*;
import net.transferproxy.ispsender.data.ServerPool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class ConfigurationTest {
    @TempDir Path dir;

    @Test
    void proxyYamlMapperReadsMultipleDefaultsAndFallbacks() {
        var config =
                ResourceUtil.getDefaultYamlMapper()
                        .readValue(
                                """
                                default-server:
                                  - first.example:25565
                                  - second.example:25566
                                  - first.example:25565
                                fallback-servers:
                                  - backup1.example:25565
                                  - backup2.example:25565
                                fallback-enabled: false
                                asn-servers:
                                  AS4766: [one.example, two.example]
                                """,
                                ISPSenderConfiguration.class);
        assertEquals(3, ServerPool.parse(config.getDefaultServer(), s -> fail(s)).size());
        assertEquals(2, ServerPool.parse(config.getFallbackServers(), s -> fail(s)).size());
        assertFalse(config.isFallbackEnabled());
        assertEquals(
                2, ServerPool.parse(config.getAsnServers().get("AS4766"), s -> fail(s)).size());
    }

    @Test
    void savesAreAtomicValidatedAndSecretsAreRedacted() throws Exception {
        var config = new ISPSenderConfiguration();
        config.setToken("private-token");
        config.setWebPassword("secret-password");
        Path path = dir.resolve("config.yml");
        var store = new ConfigurationStore(path, config);
        store.save(
                Map.of(
                        "default-server",
                        List.of("one.example", "two.example"),
                        "fallback-enabled",
                        false));
        var loaded = ResourceUtil.readYaml(path, ISPSenderConfiguration.class);
        assertEquals(2, ((List<?>) loaded.getDefaultServer()).size());
        assertFalse(loaded.isFallbackEnabled());
        assertEquals("", store.values(true).get("token"));
        assertEquals("", store.values(true).get("web-password"));
        store.save(Map.of("token", "", "web-password", ""));
        assertEquals("private-token", store.current().config().getToken());
        assertEquals("secret-password", store.current().config().getWebPassword());
        String before = Files.readString(path);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("health-check-timeout-millis", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("default-server", List.of("host:70000"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("database-file", "../outside")));
        assertThrows(IllegalArgumentException.class, () -> store.save(Map.of("unknown", true)));
        assertEquals(before, Files.readString(path));
        assertEquals(2000, store.current().config().getHealthCheckTimeoutMillis());
    }

    @Test
    void shippedConfigurationCanBeLoadedWithTheActualProxyMapper() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var c =
                    ResourceUtil.getDefaultYamlMapper()
                            .readValue(input, ISPSenderConfiguration.class);
            c.validate();
        }
    }

    @Test
    void panelProblemsDoNotStopRoutingOrConsoleCommands() throws Exception {
        var config = new ISPSenderConfiguration();
        config.setWebEnabled(true);
        config.setWebPassword("short");
        // A Tailscale address that isn't assigned here, like right after a reboot.
        config.setWebBind("100.100.100.100");
        var store = new ConfigurationStore(dir.resolve("config.yml"), config);
        assertNotNull(store.current().routing());
        store.save(Map.of("fallback-enabled", false));
        assertFalse(store.current().config().isFallbackEnabled());
        // Still refused: changing panel settings to something unsafe.
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("web-bind", "0.0.0.0", "web-password", "long-enough-pw-1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("web-bind", "127.0.0.1", "web-password", "short2")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new net.transferproxy.ispsender.web.WebPanel(store, null, null, null, null, null));
    }

    @Test
    void webhookIsRedactedValidatedOnlyWhenChangedAndNeverStopsRouting() throws Exception {
        var config = new ISPSenderConfiguration();
        config.setDiscordAlertsEnabled(true);
        // A bad alert URL already on disk must not stop startup or routing.
        config.setDiscordWebhookUrl("not a webhook");
        config.validate();
        var store = new ConfigurationStore(dir.resolve("config.yml"), config);
        assertNotNull(store.current().routing());
        store.save(Map.of("fallback-enabled", false));
        assertFalse(store.current().config().isFallbackEnabled());
        assertEquals("", store.values(true).get("discord-webhook-url"));
        var rejected =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                store.save(
                                        Map.of(
                                                "discord-webhook-url",
                                                "https://evil.example/api/webhooks/1/secret-token")));
        assertFalse(rejected.getMessage().contains("evil.example"));
        assertFalse(rejected.getMessage().contains("secret-token"));
        store.save(Map.of("discord-webhook-url", ""));
        assertEquals("not a webhook", store.current().config().getDiscordWebhookUrl());
        String valid = "https://discordapp.com/api/v10/webhooks/123/abc_DEF-1";
        store.save(Map.of("discord-webhook-url", valid));
        assertEquals(valid, store.current().config().getDiscordWebhookUrl());
        assertEquals("", store.values(true).get("discord-webhook-url"));
        assertTrue(Files.readString(dir.resolve("config.yml")).contains("abc_DEF-1"));
    }

    @Test
    void reloadAppliesTheFileAsWrittenSoABlankSecretStaysBlank() throws Exception {
        var config = new ISPSenderConfiguration();
        config.setDiscordAlertsEnabled(true);
        config.setToken("private-token");
        Path path = dir.resolve("config.yml");
        var store = new ConfigurationStore(path, config);
        store.save(
                Map.of(
                        "discord-webhook-url",
                        "https://discordapp.com/api/v10/webhooks/123/abc_DEF-1"));
        assertTrue(Files.readString(path).contains("abc_DEF-1"));
        // The operator blanks the webhook and token in config.yml, then runs `ispsender reload`.
        var edited = ResourceUtil.readYaml(path, ISPSenderConfiguration.class);
        edited.setDiscordWebhookUrl("");
        edited.setToken("");
        edited.setFallbackEnabled(false);
        Files.writeString(path, ResourceUtil.getDefaultYamlMapper().writeValueAsString(edited));
        store.reload();
        assertEquals("", store.current().config().getDiscordWebhookUrl());
        assertEquals("", store.current().config().getToken());
        assertFalse(store.current().config().isFallbackEnabled());
        String onDisk = Files.readString(path);
        assertFalse(onDisk.contains("abc_DEF-1"));
        assertFalse(onDisk.contains("private-token"));
        var reread = ResourceUtil.readYaml(path, ISPSenderConfiguration.class);
        assertEquals("", reread.getDiscordWebhookUrl());
    }

    @Test
    void healthModeAndFailureThresholdAreValidatedBeforeTheyApply() throws Exception {
        var store = new ConfigurationStore(dir.resolve("config.yml"), new ISPSenderConfiguration());
        assertEquals(
                net.transferproxy.ispsender.health.HealthMonitor.Mode.MINECRAFT,
                store.current().config().healthMode());
        assertEquals(2, store.current().config().getHealthFailureThreshold());
        store.save(Map.of("health-check-mode", "TCP", "health-failure-threshold", 3));
        assertEquals(
                net.transferproxy.ispsender.health.HealthMonitor.Mode.TCP,
                store.current().config().healthMode());
        assertEquals(3, store.current().config().getHealthFailureThreshold());
        assertThrows(
                IllegalArgumentException.class, () -> store.save(Map.of("health-check-mode", "udp")));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("health-failure-threshold", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(Map.of("health-failure-threshold", 11)));
        assertEquals(3, store.current().config().getHealthFailureThreshold());
    }

    @Test
    void databaseCannotReplaceTheConfig() throws Exception {
        var store = new ConfigurationStore(dir.resolve("config.yml"), new ISPSenderConfiguration());
        for (String name : new String[] {"config.yml", "CONFIG.YML"})
            assertThrows(
                    IllegalArgumentException.class, () -> store.save(Map.of("database-file", name)));
    }
}
