package net.transferproxy.ispsender;

import net.transferproxy.api.event.EventType;
import net.transferproxy.api.event.listener.ReadyListener;
import net.transferproxy.api.plugin.Plugin;
import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.database.DatabaseDownloader;
import net.transferproxy.ispsender.database.IPInfoDatabase;
import net.transferproxy.ispsender.listener.ISPReadyListener;
import net.transferproxy.ispsender.routing.RoutingTable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Transfers players to a server chosen from the ASN and the country of their IP address, resolved with the
 * IPinfo Lite database.
 */
public final class ISPSenderPlugin implements Plugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(ISPSenderPlugin.class);

    private final IPInfoDatabase database = new IPInfoDatabase();

    private ScheduledExecutorService executor;

    @Override
    public void onEnable() {
        final ISPSenderConfiguration config = this.makeConfiguration(ISPSenderConfiguration.class);
        final RoutingTable routingTable = RoutingTable.build(config);

        final Path databasePath = this.getDirectoryPath().resolve(config.getDatabaseFile());
        final DatabaseDownloader downloader = new DatabaseDownloader(config, databasePath);

        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ISPSender-Database");
            thread.setDaemon(true);
            return thread;
        });

        final boolean loaded = Files.isRegularFile(databasePath) && this.database.loadFrom(databasePath);
        if (loaded) {
            if (config.isUpdateOnStart()) {
                // A database is already usable: update it in background to not delay the proxy startup.
                this.executor.execute(() -> downloader.download(this.database));
            }
        } else {
            LOGGER.info("No usable local database found, downloading it now...");
            if (!downloader.download(this.database)) {
                LOGGER.error("ISPSender starts without database, every player will be sent to the default server!");
            }
        }

        final int interval = config.getUpdateIntervalMinutes();
        if (interval > 0) {
            this.executor.scheduleWithFixedDelay(() -> downloader.download(this.database), interval, interval, TimeUnit.MINUTES);
            LOGGER.info("The database will be updated every {} minute(s)", interval);
        }

        this.getEventManager().<ReadyListener>addListener(EventType.READY, new ISPReadyListener(this.database, routingTable, config));
    }

    @Override
    public void onDisable() {
        final ScheduledExecutorService currentExecutor = this.executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            this.executor = null;
        }
        this.database.close();
    }

    public @NotNull IPInfoDatabase getDatabase() {
        return this.database;
    }

}
