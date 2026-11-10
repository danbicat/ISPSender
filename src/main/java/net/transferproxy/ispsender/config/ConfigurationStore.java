package net.transferproxy.ispsender.config;

import net.transferproxy.api.util.ResourceUtil;
import net.transferproxy.ispsender.data.ServerPool;
import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.routing.RoutingTable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Validates before atomically persisting and publishing a complete settings snapshot. */
public final class ConfigurationStore {
    public record Snapshot(ISPSenderConfiguration config, RoutingTable routing) {}

    private final Path path;
    private volatile Snapshot current;

    public ConfigurationStore(Path path, ISPSenderConfiguration config) {
        this.path = path;
        // The panel validates its own settings when it starts.
        config.validateCore();
        current = new Snapshot(config, RoutingTable.build(config));
    }

    public Snapshot current() {
        return current;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> values(boolean redact) {
        Map<String, Object> values =
                new LinkedHashMap<>(
                        ResourceUtil.getDefaultYamlMapper()
                                .convertValue(current.config(), Map.class));
        if (redact) {
            values.put("token", "");
            values.put("web-password", "");
            values.put("discord-webhook-url", "");
        }
        return values;
    }

    public synchronized Snapshot save(Map<String, Object> changes) throws IOException {
        Map<String, Object> values = values(false);
        for (var entry : changes.entrySet()) {
            if (!values.containsKey(entry.getKey()))
                throw new IllegalArgumentException("Unknown setting: " + entry.getKey());
            if ((entry.getKey().equals("token") || entry.getKey().equals("web-password"))
                    && "".equals(entry.getValue())) continue;
            // A blank webhook keeps the saved one, like the token.
            if (entry.getKey().equals("discord-webhook-url")
                    && (entry.getValue() == null || "".equals(entry.getValue()))) continue;
            values.put(entry.getKey(), entry.getValue());
        }
        return commit(values);
    }

    /**
     * Applies config.yml exactly as written. Unlike {@link #save}, a blank secret in the file stays
     * blank, and nothing from the active configuration is merged in.
     */
    @SuppressWarnings("unchecked")
    public synchronized Snapshot reload() throws IOException {
        ISPSenderConfiguration fromFile = ResourceUtil.readYaml(path, ISPSenderConfiguration.class);
        return commit(ResourceUtil.getDefaultYamlMapper().convertValue(fromFile, Map.class));
    }

    private Snapshot commit(Map<String, Object> values) throws IOException {
        ISPSenderConfiguration next =
                ResourceUtil.getDefaultYamlMapper()
                        .convertValue(values, ISPSenderConfiguration.class);
        next.validateCore();
        ISPSenderConfiguration previous = current.config();
        // Only a changed webhook is checked, so an old bad value never blocks other saves.
        if (!Objects.equals(next.getDiscordWebhookUrl(), previous.getDiscordWebhookUrl()))
            next.validateAlerts();
        if (next.isWebEnabled() != previous.isWebEnabled()
                || !Objects.equals(next.getWebBind(), previous.getWebBind())
                || !Objects.equals(next.getWebPassword(), previous.getWebPassword()))
            next.validatePanel();
        validateRoutes(next);
        RoutingTable routing = RoutingTable.build(next);
        if (routing.allServers().size() > 4096)
            throw new IllegalArgumentException("Maximum 4096 distinct servers");
        Snapshot snapshot = new Snapshot(next, routing);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "config-", ".tmp");
        try {
            Files.writeString(temp, ResourceUtil.getDefaultYamlMapper().writeValueAsString(next));
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        current = snapshot;
        return snapshot;
    }

    private static void validateRoutes(ISPSenderConfiguration config) {
        List<String> errors = new ArrayList<>();
        for (var entry : config.getAsnServers().entrySet()) {
            if (IPInfoRecord.parseAsn(entry.getKey()) == IPInfoRecord.UNKNOWN_ASN)
                errors.add("Invalid ASN: " + entry.getKey());
            ServerPool.parse(entry.getValue(), errors::add);
        }
        for (var entry : config.getCountryServers().entrySet()) {
            if (entry.getKey().isBlank()) errors.add("Empty country rule");
            ServerPool.parse(entry.getValue(), errors::add);
        }
        ServerPool.parse(config.getDefaultServer(), errors::add);
        ServerPool.parse(config.getFallbackServers(), errors::add);
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Invalid routing entries: " + errors);
    }
}
