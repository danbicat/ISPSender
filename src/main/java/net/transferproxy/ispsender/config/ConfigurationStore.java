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
        config.validate();
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
            values.put(entry.getKey(), entry.getValue());
        }
        ISPSenderConfiguration next =
                ResourceUtil.getDefaultYamlMapper()
                        .convertValue(values, ISPSenderConfiguration.class);
        next.validate();
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
