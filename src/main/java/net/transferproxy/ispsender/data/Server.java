package net.transferproxy.ispsender.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A transfer target, parsed from the {@code HOST:PORT} form used in the configuration. */
public record Server(@NotNull String host, int port) {

    public static final int DEFAULT_PORT = 25565;

    /**
     * Parses a {@code HOST} or {@code HOST:PORT} string.
     *
     * @param value the raw configuration value
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
        final String host;
        final String portText;
        if (trimmed.startsWith("[")) {
            int end = trimmed.indexOf(']');
            if (end < 2) return null;
            host = trimmed.substring(1, end);
            try {
                if (!(java.net.InetAddress.getByName(host) instanceof java.net.Inet6Address))
                    return null;
            } catch (java.net.UnknownHostException e) {
                return null;
            }
            if (end == trimmed.length() - 1) return new Server(host, DEFAULT_PORT);
            if (trimmed.charAt(end + 1) != ':') return null;
            portText = trimmed.substring(end + 2);
        } else {
            int index = trimmed.lastIndexOf(':');
            host = index == -1 ? trimmed : trimmed.substring(0, index).trim();
            if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9_.-]+"))
                return null;
            if (index == -1) return new Server(host, DEFAULT_PORT);
            portText = trimmed.substring(index + 1);
        }
        final int port;
        try {
            port = Integer.parseInt(portText.trim());
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
        return (this.host.contains(":") ? "[" + this.host + "]" : this.host) + ":" + this.port;
    }
}
