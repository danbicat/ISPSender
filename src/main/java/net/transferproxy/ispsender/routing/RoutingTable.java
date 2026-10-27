package net.transferproxy.ispsender.routing;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.data.ServerPool;
import net.transferproxy.ispsender.database.IPInfoRecord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The redirection rules, resolved in this order: ASN, then country, then the default pool.
 *
 * <p>Every rule holds one or several servers; when it holds several, one is picked at random for
 * each connection.
 */
public final class RoutingTable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingTable.class);

    private final Map<Long, ServerPool> asnServers;
    private final Map<String, ServerPool> countryServers;
    private final ServerPool defaultServers;
    private final ServerPool fallbackServers;

    private RoutingTable(
            final @NotNull Map<Long, ServerPool> asnServers,
            final @NotNull Map<String, ServerPool> countryServers,
            final @Nullable ServerPool defaultServers,
            final @Nullable ServerPool fallbackServers) {
        this.asnServers = Objects.requireNonNull(asnServers, "asnServers must not be null");
        this.countryServers =
                Objects.requireNonNull(countryServers, "countryServers must not be null");
        this.defaultServers = defaultServers;
        this.fallbackServers = fallbackServers;
    }

    /**
     * Builds the routing table from the configuration, ignoring (and logging) every invalid entry.
     *
     * @param config the plugin configuration
     * @return the built routing table
     */
    public static @NotNull RoutingTable build(final @NotNull ISPSenderConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");

        final Map<Long, ServerPool> asnServers = new HashMap<>();
        forEachEntry(
                config.getAsnServers(),
                "asn-servers",
                (key, pool) -> {
                    final long asn = IPInfoRecord.parseAsn(key);
                    if (asn == IPInfoRecord.UNKNOWN_ASN) {
                        LOGGER.warn("Invalid ASN '{}' in asn-servers, entry ignored", key);
                        return;
                    }
                    final ServerPool previous = asnServers.put(asn, pool);
                    if (previous != null) {
                        LOGGER.warn(
                                "Duplicated ASN 'AS{}' in asn-servers, '{}' replaced by '{}'",
                                asn,
                                previous,
                                pool);
                    }
                });

        final Map<String, ServerPool> countryServers = new HashMap<>();
        forEachEntry(
                config.getCountryServers(),
                "country-servers",
                (key, pool) -> {
                    final String country = normalize(key);
                    if (country == null) {
                        LOGGER.warn("Empty country key in country-servers, entry ignored");
                        return;
                    }
                    countryServers.put(country, pool);
                });

        final ServerPool defaultServers =
                ServerPool.parse(
                        config.getDefaultServer(),
                        value ->
                                LOGGER.warn(
                                        "Invalid server '{}' in default-server, value ignored",
                                        value));

        if (defaultServers != null) {
            LOGGER.info(
                    "Loaded {} ASN rule(s), {} country rule(s), default server(s): {}",
                    asnServers.size(),
                    countryServers.size(),
                    defaultServers);
        } else {
            LOGGER.info(
                    "Loaded {} ASN rule(s), {} country rule(s) and no default server, unmatched"
                        + " players will be kicked",
                    asnServers.size(),
                    countryServers.size());
        }

        final ServerPool fallback =
                ServerPool.parse(
                        config.getFallbackServers(),
                        value -> LOGGER.warn("Invalid fallback server '{}', ignored", value));
        return new RoutingTable(
                Map.copyOf(asnServers), Map.copyOf(countryServers), defaultServers, fallback);
    }

    /**
     * Resolves the server a connection must be transferred to. When the matched rule holds several
     * servers, one of them is picked at random.
     *
     * @param record the record of the connection address, or null when it could not be resolved
     * @return the matching route, or null when no rule matched and no default server is configured
     */
    public @Nullable Route resolve(final @Nullable IPInfoRecord record) {
        return resolve(record, server -> true, true);
    }

    public @Nullable Route resolve(
            final @Nullable IPInfoRecord record,
            Predicate<Server> available,
            boolean fallbackEnabled) {
        if (record != null) {
            Route route =
                    route(asnServers.get(record.asnNumber()), "AS" + record.asnNumber(), available);
            if (route != null) return route;
            route =
                    route(
                            countryPool(record.countryCode()),
                            "country " + record.countryCode(),
                            available);
            if (route != null) return route;
            route = route(countryPool(record.country()), "country " + record.country(), available);
            if (route != null) return route;
        }
        Route route = route(defaultServers, "default", available);
        if (route != null) return route;
        return fallbackEnabled ? route(fallbackServers, "fallback", available) : null;
    }

    private ServerPool countryPool(String value) {
        String key = normalize(value);
        return key == null ? null : countryServers.get(key);
    }

    private static @Nullable Route route(
            ServerPool pool, String rule, Predicate<Server> available) {
        Server server = pool == null ? null : pool.pick(available);
        return server == null ? null : new Route(server, rule);
    }

    public Set<Server> allServers() {
        Set<Server> result = new HashSet<>();
        asnServers.values().forEach(pool -> result.addAll(pool.servers()));
        countryServers.values().forEach(pool -> result.addAll(pool.servers()));
        if (defaultServers != null) result.addAll(defaultServers.servers());
        if (fallbackServers != null) result.addAll(fallbackServers.servers());
        return Set.copyOf(result);
    }

    private static @Nullable String normalize(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static void forEachEntry(
            final @Nullable Map<String, Object> entries,
            final @NotNull String section,
            final @NotNull EntryConsumer consumer) {
        if (entries == null) {
            return;
        }
        for (final Map.Entry<String, Object> entry : entries.entrySet()) {
            final String key = entry.getKey();
            final ServerPool pool =
                    ServerPool.parse(
                            entry.getValue(),
                            value ->
                                    LOGGER.warn(
                                            "Invalid server '{}' for '{}' in {}, value ignored",
                                            value,
                                            key,
                                            section));
            if (pool == null) {
                LOGGER.warn("No usable server for '{}' in {}, entry ignored", key, section);
                continue;
            }
            consumer.accept(key, pool);
        }
    }

    @FunctionalInterface
    private interface EntryConsumer {

        void accept(final @NotNull String key, final @NotNull ServerPool pool);
    }

    public int getAsnRuleCount() {
        return this.asnServers.size();
    }

    public int getCountryRuleCount() {
        return this.countryServers.size();
    }

    public @Nullable ServerPool getDefaultServers() {
        return this.defaultServers;
    }
}
