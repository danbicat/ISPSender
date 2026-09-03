package net.transferproxy.ispsender.routing;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.data.ServerPool;
import net.transferproxy.ispsender.database.IPInfoRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The redirection rules, resolved in this order: ASN, then country, then the default pool.
 * <p>
 * Every rule holds one or several servers; when it holds several, one is picked at random for each connection.
 */
public final class RoutingTable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingTable.class);

    private final Map<Long, ServerPool> asnServers;
    private final Map<String, ServerPool> countryServers;
    private final ServerPool defaultServers;

    private RoutingTable(final @NotNull Map<Long, ServerPool> asnServers,
                         final @NotNull Map<String, ServerPool> countryServers,
                         final @Nullable ServerPool defaultServers) {
        this.asnServers = Objects.requireNonNull(asnServers, "asnServers must not be null");
        this.countryServers = Objects.requireNonNull(countryServers, "countryServers must not be null");
        this.defaultServers = defaultServers;
    }

    /**
     * Builds the routing table from the configuration, ignoring (and logging) every invalid entry.
     *
     * @param config the plugin configuration
     *
     * @return the built routing table
     */
    public static @NotNull RoutingTable build(final @NotNull ISPSenderConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");

        final Map<Long, ServerPool> asnServers = new HashMap<>();
        forEachEntry(config.getAsnServers(), "asn-servers", (key, pool) -> {
            final long asn = IPInfoRecord.parseAsn(key);
            if (asn == IPInfoRecord.UNKNOWN_ASN) {
                LOGGER.warn("Invalid ASN '{}' in asn-servers, entry ignored", key);
                return;
            }
            final ServerPool previous = asnServers.put(asn, pool);
            if (previous != null) {
                LOGGER.warn("Duplicated ASN 'AS{}' in asn-servers, '{}' replaced by '{}'", asn, previous, pool);
            }
        });

        final Map<String, ServerPool> countryServers = new HashMap<>();
        forEachEntry(config.getCountryServers(), "country-servers", (key, pool) -> {
            final String country = normalize(key);
            if (country == null) {
                LOGGER.warn("Empty country key in country-servers, entry ignored");
                return;
            }
            countryServers.put(country, pool);
        });

        final ServerPool defaultServers = ServerPool.parse(config.getDefaultServer(),
                value -> LOGGER.warn("Invalid server '{}' in default-server, value ignored", value));

        if (defaultServers != null) {
            LOGGER.info("Loaded {} ASN rule(s), {} country rule(s), default server(s): {}",
                    asnServers.size(),
                    countryServers.size(),
                    defaultServers);
        } else {
            LOGGER.info("Loaded {} ASN rule(s), {} country rule(s) and no default server, unmatched players will be kicked",
                    asnServers.size(),
                    countryServers.size());
        }

        return new RoutingTable(Map.copyOf(asnServers), Map.copyOf(countryServers), defaultServers);
    }

    /**
     * Resolves the server a connection must be transferred to. When the matched rule holds several servers, one of
     * them is picked at random.
     *
     * @param record the record of the connection address, or null when it could not be resolved
     *
     * @return the matching route, or null when no rule matched and no default server is configured
     */
    public @Nullable Route resolve(final @Nullable IPInfoRecord record) {
        if (record != null) {
            final long asn = record.asnNumber();
            if (asn != IPInfoRecord.UNKNOWN_ASN) {
                final ServerPool pool = this.asnServers.get(asn);
                if (pool != null) {
                    return route(pool, "AS" + asn);
                }
            }
            final Route byCode = this.resolveCountry(record.countryCode());
            if (byCode != null) {
                return byCode;
            }
            final Route byName = this.resolveCountry(record.country());
            if (byName != null) {
                return byName;
            }
        }
        return this.defaultServers != null ? route(this.defaultServers, "default") : null;
    }

    private @Nullable Route resolveCountry(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final String country = normalize(value);
        if (country == null) {
            return null;
        }
        final ServerPool pool = this.countryServers.get(country);
        return pool != null ? route(pool, "country " + value.trim()) : null;
    }

    private static @NotNull Route route(final @NotNull ServerPool pool, final @NotNull String rule) {
        return new Route(pool.pick(), pool.size() > 1 ? rule + ", random of " + pool.size() : rule);
    }

    private static @Nullable String normalize(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static void forEachEntry(final @Nullable Map<String, Object> entries,
                                     final @NotNull String section,
                                     final @NotNull EntryConsumer consumer) {
        if (entries == null) {
            return;
        }
        for (final Map.Entry<String, Object> entry : entries.entrySet()) {
            final String key = entry.getKey();
            final ServerPool pool = ServerPool.parse(entry.getValue(),
                    value -> LOGGER.warn("Invalid server '{}' for '{}' in {}, value ignored", value, key, section));
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
