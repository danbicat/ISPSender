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
}
