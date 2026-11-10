package net.transferproxy.ispsender.config;

import net.transferproxy.ispsender.health.HealthMonitor;

import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Explicit setters also handle untyped YAML lists reliably with Jackson 3. */
public final class ISPSenderConfiguration {
    public static final String TOKEN_PLACEHOLDER = "YOUR_IPINFO_TOKEN";
    public static final String TOKEN_ENVIRONMENT_KEY = "IPINFO_TOKEN";
    public static final String DISCORD_WEBHOOK_ENVIRONMENT_KEY = "ISPSENDER_DISCORD_WEBHOOK";
    private static final Pattern DISCORD_WEBHOOK =
            Pattern.compile(
                    "^https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+$");
    private String token = TOKEN_PLACEHOLDER;
    private String databaseUrl = "https://ipinfo.io/data/ipinfo_lite.mmdb?token={token}";
    private String databaseFile = "ipinfo_lite.mmdb";
    // Accepted for compatibility. Daily freshness always takes precedence over legacy intervals.
    private boolean updateOnStart = true;
    private int updateIntervalMinutes;
    private String databaseTimezone = "Asia/Seoul";
    private int connectTimeoutSeconds = 15;
    private int readTimeoutSeconds = 180;
    private boolean logLookups = true;
    private Map<String, Object> asnServers = Map.of();
    private Map<String, Object> countryServers = Map.of();
    private Object defaultServer = List.of();
    private Object fallbackServers = List.of();
    private boolean fallbackEnabled = true;
    private int healthCheckTimeoutMillis = 2000;
    private String healthCheckMode = "minecraft";
    private int healthFailureThreshold = 2;
    private String noServerMessage = "<red>No server is available for your connection.";
    private boolean webEnabled;
    private String webBind = "127.0.0.1";
    private int webPort = 8080;
    private String webPassword = "";
    private int connectionLogRetentionDays = 30;
    private boolean discordAlertsEnabled;
    private String discordWebhookUrl = "";

    public @Nullable String resolveToken() {
        final String env = System.getenv(TOKEN_ENVIRONMENT_KEY);
        final String value = env != null && !env.isBlank() ? env : token;
        return value == null || value.isBlank() || value.trim().equals(TOKEN_PLACEHOLDER)
                ? null
                : value.trim();
    }

    public String resolveWebPassword() {
        final String env = System.getenv("ISPSENDER_WEB_PASSWORD");
        return env != null && !env.isBlank() ? env : webPassword;
    }

    /** The webhook in use: the environment variable when set, otherwise the configured URL. */
    public @Nullable String resolveDiscordWebhook() {
        final String env = System.getenv(DISCORD_WEBHOOK_ENVIRONMENT_KEY);
        final String value = env != null && !env.isBlank() ? env : discordWebhookUrl;
        return value == null || value.isBlank() ? null : value.trim();
    }

    public void validate() {
        validateCore();
        validatePanel();
    }

    /** Everything routing needs; a panel problem must not stop routing or the fallback command. */
    public void validateCore() {
        ZoneId.of(databaseTimezone);
        if (databaseFile == null
                || databaseFile.isBlank()
                || java.nio.file.Path.of(databaseFile).isAbsolute()
                || databaseFile.contains("/")
                || databaseFile.contains("\\")
                || databaseFile.equalsIgnoreCase("config.yml")
                || databaseFile.equals(".")
                || databaseFile.equals(".."))
            throw new IllegalArgumentException(
                    "database-file must be a file name inside the plugin directory");
        if (databaseUrl == null
                || !(databaseUrl.startsWith("https://") || databaseUrl.startsWith("http://")))
            throw new IllegalArgumentException("database-url must use HTTP or HTTPS");
        if (connectTimeoutSeconds < 1
                || connectTimeoutSeconds > 300
                || readTimeoutSeconds < 1
                || readTimeoutSeconds > 3600)
            throw new IllegalArgumentException("Invalid database timeout");
        if (healthCheckTimeoutMillis < 100 || healthCheckTimeoutMillis > 5000)
            throw new IllegalArgumentException("health-check-timeout-millis must be 100..5000");
        if (healthCheckMode == null
                || !(healthCheckMode.equalsIgnoreCase("minecraft")
                        || healthCheckMode.equalsIgnoreCase("tcp")))
            throw new IllegalArgumentException("health-check-mode must be minecraft or tcp");
        if (healthFailureThreshold < 1 || healthFailureThreshold > 10)
            throw new IllegalArgumentException("health-failure-threshold must be 1..10");
        if (webPort < 1 || webPort > 65535) throw new IllegalArgumentException("Invalid web-port");
        if (connectionLogRetentionDays < 1 || connectionLogRetentionDays > 365)
            throw new IllegalArgumentException("connection-log-retention-days must be 1..365");
        if (noServerMessage == null || webBind == null || webPassword == null)
            throw new IllegalArgumentException("Message, bind address and password cannot be null");
    }

    public void validatePanel() {
        if (!webEnabled) return;
        if (resolveWebPassword() == null || resolveWebPassword().length() < 12)
            throw new IllegalArgumentException(
                    "Set a web password of at least 12 characters (or ISPSENDER_WEB_PASSWORD)");
        net.transferproxy.ispsender.web.WebPanel.validateBindRange(webBind);
    }

    /**
     * Checks the Discord webhook URL. Blank is allowed. This is deliberately not part of
     * {@link #validateCore()}: a bad alert setting must never stop routing. The message never
     * contains the URL.
     */
    public void validateAlerts() {
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank()) return;
        if (!isDiscordWebhook(discordWebhookUrl.trim()))
            throw new IllegalArgumentException("discord-webhook-url must be a Discord webhook URL");
    }

    public static boolean isDiscordWebhook(final @Nullable String value) {
        return value != null && DISCORD_WEBHOOK.matcher(value).matches();
    }

    /** The probe mode used by health checks. */
    public HealthMonitor.Mode healthMode() {
        return "tcp".equalsIgnoreCase(healthCheckMode)
                ? HealthMonitor.Mode.TCP
                : HealthMonitor.Mode.MINECRAFT;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String v) {
        token = v;
    }

    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public void setDatabaseUrl(String v) {
        databaseUrl = v;
    }

    public String getDatabaseFile() {
        return databaseFile;
    }

    public void setDatabaseFile(String v) {
        databaseFile = v;
    }

    public boolean isUpdateOnStart() {
        return updateOnStart;
    }

    public void setUpdateOnStart(boolean v) {
        updateOnStart = v;
    }

    public int getUpdateIntervalMinutes() {
        return updateIntervalMinutes;
    }

    public void setUpdateIntervalMinutes(int v) {
        updateIntervalMinutes = v;
    }

    public String getDatabaseTimezone() {
        return databaseTimezone;
    }

    public void setDatabaseTimezone(String v) {
        databaseTimezone = v;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int v) {
        connectTimeoutSeconds = v;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int v) {
        readTimeoutSeconds = v;
    }

    public boolean isLogLookups() {
        return logLookups;
    }

    public void setLogLookups(boolean v) {
        logLookups = v;
    }

    public Map<String, Object> getAsnServers() {
        return asnServers;
    }

    public void setAsnServers(Map<String, Object> v) {
        asnServers = v == null ? Map.of() : v;
    }

    public Map<String, Object> getCountryServers() {
        return countryServers;
    }

    public void setCountryServers(Map<String, Object> v) {
        countryServers = v == null ? Map.of() : v;
    }

    public Object getDefaultServer() {
        return defaultServer;
    }

    public void setDefaultServer(Object v) {
        defaultServer = v;
    }

    public Object getFallbackServers() {
        return fallbackServers;
    }

    public void setFallbackServers(Object v) {
        fallbackServers = v;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public void setFallbackEnabled(boolean v) {
        fallbackEnabled = v;
    }

    public int getHealthCheckTimeoutMillis() {
        return healthCheckTimeoutMillis;
    }

    public void setHealthCheckTimeoutMillis(int v) {
        healthCheckTimeoutMillis = v;
    }

    public String getHealthCheckMode() {
        return healthCheckMode;
    }

    public void setHealthCheckMode(String v) {
        healthCheckMode = v;
    }

    public int getHealthFailureThreshold() {
        return healthFailureThreshold;
    }

    public void setHealthFailureThreshold(int v) {
        healthFailureThreshold = v;
    }

    public String getNoServerMessage() {
        return noServerMessage;
    }

    public void setNoServerMessage(String v) {
        noServerMessage = v;
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public void setWebEnabled(boolean v) {
        webEnabled = v;
    }

    public String getWebBind() {
        return webBind;
    }

    public void setWebBind(String v) {
        webBind = v;
    }

    public int getWebPort() {
        return webPort;
    }

    public void setWebPort(int v) {
        webPort = v;
    }

    public String getWebPassword() {
        return webPassword;
    }

    public void setWebPassword(String v) {
        webPassword = v;
    }

    public int getConnectionLogRetentionDays() {
        return connectionLogRetentionDays;
    }

    public void setConnectionLogRetentionDays(int v) {
        connectionLogRetentionDays = v;
    }

    public boolean isDiscordAlertsEnabled() {
        return discordAlertsEnabled;
    }

    public void setDiscordAlertsEnabled(boolean v) {
        discordAlertsEnabled = v;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public void setDiscordWebhookUrl(String v) {
        discordWebhookUrl = v == null ? "" : v;
    }
}
