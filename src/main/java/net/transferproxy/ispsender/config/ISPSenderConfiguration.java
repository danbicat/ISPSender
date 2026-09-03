package net.transferproxy.ispsender.config;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Deserialized content of {@code plugins/ISPSender/config.yml}.
 * <p>
 * Field values set in the constructor are the defaults used when a key is missing from the file.
 */
public final class ISPSenderConfiguration {

    public static final String TOKEN_PLACEHOLDER = "YOUR_IPINFO_TOKEN";
    public static final String TOKEN_ENVIRONMENT_KEY = "IPINFO_TOKEN";

    private final String token;
    private final String databaseUrl;
    private final String databaseFile;
    private final boolean updateOnStart;
    private final int updateIntervalMinutes;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final boolean logLookups;
    private final Map<String, Object> asnServers;
    private final Map<String, Object> countryServers;
    private final Object defaultServer;
    private final String noServerMessage;

    public ISPSenderConfiguration() {
        this.token = TOKEN_PLACEHOLDER;
        this.databaseUrl = "https://ipinfo.io/data/ipinfo_lite.mmdb?token={token}";
        this.databaseFile = "ipinfo_lite.mmdb";
        this.updateOnStart = true;
        this.updateIntervalMinutes = 0;
        this.connectTimeoutSeconds = 15;
        this.readTimeoutSeconds = 180;
        this.logLookups = false;
        this.asnServers = Map.of();
        this.countryServers = Map.of();
        this.defaultServer = List.of();
        this.noServerMessage = "<red>No server is available for your connection.";
    }

    /**
     * Returns the token to use, the {@code IPINFO_TOKEN} environment variable having priority over the file.
     *
     * @return the usable token, or null when none is configured
     */
    public @Nullable String resolveToken() {
        final String environment = System.getenv(TOKEN_ENVIRONMENT_KEY);
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        if (this.token == null || this.token.isBlank() || this.token.trim().equals(TOKEN_PLACEHOLDER)) {
            return null;
        }
        return this.token.trim();
    }

    public String getToken() {
        return this.token;
    }

    public String getDatabaseUrl() {
        return this.databaseUrl;
    }

    public String getDatabaseFile() {
        return this.databaseFile;
    }

    public boolean isUpdateOnStart() {
        return this.updateOnStart;
    }

    public int getUpdateIntervalMinutes() {
        return this.updateIntervalMinutes;
    }

    public int getConnectTimeoutSeconds() {
        return this.connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return this.readTimeoutSeconds;
    }

    public boolean isLogLookups() {
        return this.logLookups;
    }

    /**
     * @return the ASN rules, every value being a single {@code HOST:PORT} string or a list of them
     */
    public Map<String, Object> getAsnServers() {
        return this.asnServers;
    }

    /**
     * @return the country rules, every value being a single {@code HOST:PORT} string or a list of them
     */
    public Map<String, Object> getCountryServers() {
        return this.countryServers;
    }

    /**
     * @return the fallback server(s), a single {@code HOST:PORT} string or a list of them
     */
    public Object getDefaultServer() {
        return this.defaultServer;
    }

    public String getNoServerMessage() {
        return this.noServerMessage;
    }

}
