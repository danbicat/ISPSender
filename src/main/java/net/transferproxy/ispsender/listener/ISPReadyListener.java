package net.transferproxy.ispsender.listener;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.transferproxy.api.event.listener.ReadyListener;
import net.transferproxy.api.network.connection.PlayerConnection;
import net.transferproxy.ispsender.config.ConfigurationStore;
import net.transferproxy.ispsender.database.IPInfoDatabase;
import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.health.HealthMonitor;
import net.transferproxy.ispsender.logging.ConnectionLogs;
import net.transferproxy.ispsender.routing.Route;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Looks up the address of every ready connection and transfers it to the matching server. */
public final class ISPReadyListener implements ReadyListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ISPReadyListener.class);

    private final IPInfoDatabase database;
    private final ConfigurationStore settings;
    private final HealthMonitor health;
    private final ConnectionLogs logs;

    public ISPReadyListener(
            IPInfoDatabase database,
            ConfigurationStore settings,
            HealthMonitor health,
            ConnectionLogs logs) {
        this.database = database;
        this.settings = settings;
        this.health = health;
        this.logs = logs;
    }

    @Override
    public void handle(final @NotNull PlayerConnection connection) {
        final InetAddress address = getAddress(connection);
        final IPInfoRecord record = this.database.lookup(address);
        final ConfigurationStore.Snapshot snapshot = settings.current();
        final Route route =
                snapshot.routing()
                        .resolve(record, health::isAlive, snapshot.config().isFallbackEnabled());
        logs.record(connection.getName(), address, record, route);

        if (route == null) {
            LOGGER.warn(
                    "No server found for {} ({}), the player has been kicked",
                    connection.getName(),
                    describe(address, record));
            connection.disconnect(
                    MiniMessage.miniMessage().deserialize(snapshot.config().getNoServerMessage()));
            return;
        }

        if (snapshot.config().isLogLookups()) {
            LOGGER.info(
                    "{} ({}) -> {} [{}]",
                    connection.getName(),
                    describe(address, record),
                    route.server(),
                    route.rule());
        }
        connection.transfer(route.server().host(), route.server().port());
    }

    private static final Method GET_REMOTE_ADDRESS_METHOD;

    static {
        Method method = null;
        try {
            method = PlayerConnection.class.getMethod("getRemoteAddress");
        } catch (final Throwable ignored) {
            try {
                final Class<?> implClass =
                        Class.forName("net.transferproxy.network.connection.PlayerConnectionImpl");
                method = implClass.getMethod("getRemoteAddress");
            } catch (final Throwable alsoIgnored) {
            }
        }
        if (method != null) {
            method.setAccessible(true);
        }
        GET_REMOTE_ADDRESS_METHOD = method;
    }

    private static @Nullable SocketAddress getRemoteSocketAddress(
            final @NotNull PlayerConnection connection) {
        if (GET_REMOTE_ADDRESS_METHOD != null) {
            try {
                final Object result = GET_REMOTE_ADDRESS_METHOD.invoke(connection);
                if (result instanceof SocketAddress socketAddress) {
                    return socketAddress;
                }
            } catch (final Throwable ignored) {
            }
        }
        try {
            final Method method = connection.getClass().getMethod("getRemoteAddress");
            final Object result = method.invoke(connection);
            if (result instanceof SocketAddress socketAddress) {
                return socketAddress;
            }
        } catch (final Throwable ignored) {
        }
        return connection.getChannel().remoteAddress();
    }

    private static @Nullable InetAddress getAddress(final @NotNull PlayerConnection connection) {
        final SocketAddress remoteAddress = getRemoteSocketAddress(connection);
        if (remoteAddress instanceof final InetSocketAddress socketAddress) {
            final InetAddress address = socketAddress.getAddress();
            if (address != null) {
                return address;
            }
            try {
                return InetAddress.getByName(socketAddress.getHostString());
            } catch (final Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static @NotNull String describe(
            final @Nullable InetAddress address, final @Nullable IPInfoRecord record) {
        final String host = address != null ? address.getHostAddress() : "unknown address";
        return record != null
                ? host + ", " + record.describe()
                : host + ", not found in the database";
    }
}
