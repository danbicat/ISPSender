package net.transferproxy.ispsender.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A transfer target, parsed from the {@code HOST:PORT} form used in the configuration.
 */
public record Server(@NotNull String host, int port) {

    public static final int DEFAULT_PORT = 25565;

    /**
     * Parses a {@code HOST} or {@code HOST:PORT} string.
     *
     * @param value the raw configuration value
     *
     * @return the parsed server, or null when the value is empty or malformed
     */
    public static @Nullable Server parse(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final int index = trimmed.lastIndexOf(':');
        if (index == -1) {
            return new Server(trimmed, DEFAULT_PORT);
        }
        final String host = trimmed.substring(0, index).trim();
        if (host.isEmpty()) {
            return null;
        }
        final int port;
        try {
            port = Integer.parseInt(trimmed.substring(index + 1).trim());
        } catch (final NumberFormatException ignored) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new Server(host, port);
    }

    @Override
    public @NotNull String toString() {
        return this.host + ":" + this.port;
    }

}
