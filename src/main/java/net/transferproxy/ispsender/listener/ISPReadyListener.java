package net.transferproxy.ispsender.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.transferproxy.api.event.listener.ReadyListener;
import net.transferproxy.api.network.connection.PlayerConnection;
import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.database.IPInfoDatabase;
import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.routing.Route;
import net.transferproxy.ispsender.routing.RoutingTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Looks up the address of every ready connection and transfers it to the matching server.
 */
public final class ISPReadyListener implements ReadyListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ISPReadyListener.class);

    private final IPInfoDatabase database;
    private final RoutingTable routingTable;
    private final Component noServerMessage;
    private final boolean logLookups;

    public ISPReadyListener(final @NotNull IPInfoDatabase database,
                            final @NotNull RoutingTable routingTable,
                            final @NotNull ISPSenderConfiguration config) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.routingTable = Objects.requireNonNull(routingTable, "routingTable must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.noServerMessage = MiniMessage.miniMessage().deserialize(config.getNoServerMessage());
        this.logLookups = config.isLogLookups();
    }

    @Override
    public void handle(final @NotNull PlayerConnection connection) {
        final InetAddress address = getAddress(connection);
        final IPInfoRecord record = this.database.lookup(address);
        final Route route = this.routingTable.resolve(record);

        if (route == null) {
            LOGGER.warn("No server found for {} ({}), the player has been kicked",
                    connection.getName(),
                    describe(address, record));
            connection.disconnect(this.noServerMessage);
            return;
        }

        if (this.logLookups) {
            LOGGER.info("{} ({}) -> {} [{}]", connection.getName(), describe(address, record), route.server(), route.rule());
        }
        connection.transfer(route.server().host(), route.server().port());
    }

    private static @Nullable InetAddress getAddress(final @NotNull PlayerConnection connection) {
        final SocketAddress remoteAddress = connection.getChannel().remoteAddress();
        return remoteAddress instanceof final InetSocketAddress socketAddress ? socketAddress.getAddress() : null;
    }

    private static @NotNull String describe(final @Nullable InetAddress address, final @Nullable IPInfoRecord record) {
        final String host = address != null ? address.getHostAddress() : "unknown address";
        return record != null ? host + ", " + record.describe() : host + ", not found in the database";
    }

}
