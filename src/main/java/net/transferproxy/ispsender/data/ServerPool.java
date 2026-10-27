package net.transferproxy.ispsender.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The servers a rule can send a player to. A random one is picked for every connection, which
 * spreads the players over the pool. Listing the same server twice simply doubles its chances.
 */
public final class ServerPool {

    private final Server[] servers;

    private ServerPool(final Server @NotNull [] servers) {
        this.servers = Objects.requireNonNull(servers, "servers must not be null");
        if (servers.length == 0) {
            throw new IllegalArgumentException("servers must not be empty");
        }
    }

    /**
     * Parses a configuration value, which is either a single {@code HOST:PORT} string or a list of
     * them.
     *
     * @param value the raw configuration value
     * @param invalidCollector called with every value that could not be parsed
     * @return the pool, or null when the value holds no usable server
     */
    public static @Nullable ServerPool parse(
            final @Nullable Object value,
            final @NotNull Consumer<@NotNull String> invalidCollector) {
        Objects.requireNonNull(invalidCollector, "invalidCollector must not be null");
        if (value == null) {
            return null;
        }
        final List<Server> servers = new ArrayList<>();
        for (final Object element : flatten(value)) {
            final String raw = element instanceof String text ? text : "";
            if (!(element instanceof String)) {
                invalidCollector.accept(String.valueOf(element));
                continue;
            }
            final Server server = Server.parse(raw);
            if (server == null) {
                if (!raw.isBlank()) {
                    invalidCollector.accept(raw);
                }
                continue;
            }
            servers.add(server);
        }
        return servers.isEmpty() ? null : new ServerPool(servers.toArray(Server[]::new));
    }

    private static @NotNull Collection<?> flatten(final @NotNull Object value) {
        return value instanceof final Collection<?> collection ? collection : List.of(value);
    }

    /**
     * @return one of the servers of this pool, picked at random
     */
    public @NotNull Server pick() {
        return this.servers.length == 1
                ? this.servers[0]
                : this.servers[ThreadLocalRandom.current().nextInt(this.servers.length)];
    }

    public @Nullable Server pick(final Predicate<Server> available) {
        final List<Server> alive = new ArrayList<>();
        for (Server server : servers) if (available.test(server)) alive.add(server);
        return alive.isEmpty()
                ? null
                : alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
    }

    public List<Server> servers() {
        return List.of(servers);
    }

    /**
     * @return the number of servers in this pool
     */
    public int size() {
        return this.servers.length;
    }

    @Override
    public @NotNull String toString() {
        if (this.servers.length == 1) {
            return this.servers[0].toString();
        }
        final StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (final Server server : this.servers) {
            joiner.add(server.toString());
        }
        return joiner.toString();
    }
}
