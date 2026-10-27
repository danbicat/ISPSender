package net.transferproxy.ispsender;

import net.transferproxy.api.event.EventType;
import net.transferproxy.api.event.listener.ReadyListener;
import net.transferproxy.api.plugin.Plugin;
import net.transferproxy.api.terminal.BaseTerminalExecutor;
import net.transferproxy.ispsender.config.*;
import net.transferproxy.ispsender.database.*;
import net.transferproxy.ispsender.health.HealthMonitor;
import net.transferproxy.ispsender.listener.ISPReadyListener;
import net.transferproxy.ispsender.logging.ConnectionLogs;
import net.transferproxy.ispsender.web.WebPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.*;
import java.util.Map;

public final class ISPSenderPlugin implements Plugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ISPSenderPlugin.class);
    private final IPInfoDatabase database = new IPInfoDatabase();
    private ConfigurationStore settings;
    private DailyDatabaseUpdater updater;
    private HealthMonitor health;
    private ConnectionLogs logs;
    private WebPanel panel;

    @Override
    public void onEnable() {
        ISPSenderConfiguration config = makeConfiguration(ISPSenderConfiguration.class);
        settings = new ConfigurationStore(getConfigPath(), config);
        Path databasePath = getDirectoryPath().resolve(config.getDatabaseFile());
        if (Files.isRegularFile(databasePath)) database.loadFrom(databasePath);
        updater =
                new DailyDatabaseUpdater(
                        databasePath,
                        Clock.systemUTC(),
                        () -> ZoneId.of(settings.current().config().getDatabaseTimezone()),
                        () ->
                                new DatabaseDownloader(settings.current().config(), databasePath)
                                        .download(database));
        if (!database.isLoaded()) updater.invalidate();
        health =
                new HealthMonitor(
                        () -> settings.current().routing().allServers(),
                        () -> settings.current().config().getHealthCheckTimeoutMillis());
        logs =
                new ConnectionLogs(
                        getDirectoryPath().resolve("connection-logs"),
                        () -> ZoneId.of(settings.current().config().getDatabaseTimezone()),
                        () -> settings.current().config().getConnectionLogRetentionDays());
        health.start();
        updater.start();
        getEventManager()
                .<ReadyListener>addListener(
                        EventType.READY, new ISPReadyListener(database, settings, health, logs));
        if (getModuleManager().getTerminalExecutor() instanceof BaseTerminalExecutor terminal) {
            terminal.register(
                    "fallback",
                    "fallback on|off|status: control the emergency server pool",
                    this::fallbackCommand);
            terminal.register(
                    "ispsender",
                    "ispsender reload: reload config.yml",
                    args -> {
                        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                            LOGGER.info("Usage: ispsender reload");
                            return;
                        }
                        try {
                            ISPSenderConfiguration next =
                                    net.transferproxy.api.util.ResourceUtil.readYaml(
                                            getConfigPath(), ISPSenderConfiguration.class);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> values =
                                    net.transferproxy.api.util.ResourceUtil.getDefaultYamlMapper()
                                            .convertValue(next, Map.class);
                            settings.save(values);
                            health.refresh();
                            updater.checkSoon();
                            LOGGER.info(
                                    "Configuration reloaded. Web listener/password and"
                                        + " database-file changes require a proxy restart.");
                        } catch (Exception e) {
                            LOGGER.error(
                                    "Reload failed; active configuration retained ({})",
                                    e.getClass().getSimpleName());
                        }
                    });
        } else
            LOGGER.warn(
                    "Custom terminal executor: fallback commands unavailable; use config.yml or web"
                        + " panel");
        if (config.isWebEnabled()) {
            try {
                panel =
                        new WebPanel(
                                settings,
                                health,
                                database,
                                updater,
                                logs,
                                () -> {
                                    health.refresh();
                                    updater.checkSoon();
                                });
                panel.start();
                LOGGER.info(
                        "Web panel listening at {}:{} (password required)",
                        config.getWebBind(),
                        config.getWebPort());
            } catch (Exception e) {
                LOGGER.error("Cannot start web panel ({})", e.getClass().getSimpleName());
            }
        }
        LOGGER.info(
                "Database updates daily at 00:00 {}, with catch-up after downtime",
                config.getDatabaseTimezone());
    }

    private void fallbackCommand(String[] args) {
        if (args.length == 1
                && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            try {
                settings.save(Map.of("fallback-enabled", args[0].equalsIgnoreCase("on")));
            } catch (Exception e) {
                LOGGER.error("Cannot save fallback setting ({})", e.getClass().getSimpleName());
                return;
            }
        } else if (args.length > 1 || args.length == 1 && !args[0].equalsIgnoreCase("status")) {
            LOGGER.info("Usage: fallback on|off|status");
            return;
        }
        LOGGER.info(
                "Fallback is {}", settings.current().config().isFallbackEnabled() ? "ON" : "OFF");
    }

    @Override
    public void onDisable() {
        if (panel != null) panel.close();
        if (health != null) health.close();
        if (updater != null) updater.close();
        if (logs != null) logs.close();
        database.close();
    }

    public IPInfoDatabase getDatabase() {
        return database;
    }
}
