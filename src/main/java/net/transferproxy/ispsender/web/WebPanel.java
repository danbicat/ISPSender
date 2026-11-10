package net.transferproxy.ispsender.web;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;

import net.transferproxy.ispsender.config.*;
import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.database.*;
import net.transferproxy.ispsender.dns.SrvResolver;
import net.transferproxy.ispsender.health.HealthMonitor;
import net.transferproxy.ispsender.logging.ConnectionLogs;
import net.transferproxy.ispsender.routing.RoutingTable;

import tools.jackson.databind.json.JsonMapper;

import javax.naming.NamingException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** Private management listener; never accepts a wildcard or public bind address. */
public final class WebPanel implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static final JsonMapper JSON_READER = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_BODY = 256 * 1024;
    private static final long SESSION_MS = 12 * 60 * 60 * 1000L;

    private record Session(String csrf, long expires) {}

    private record Attempts(int count, long until) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<InetAddress, Attempts> attempts = new ConcurrentHashMap<>();
    private final ConfigurationStore store;
    private final HealthMonitor health;
    private final IPInfoDatabase database;
    private final DailyDatabaseUpdater updater;
    private final ConnectionLogs logs;
    private final Runnable onSave;
    // Address checks may take several seconds each, so only two run at a time.
    private final Semaphore checks = new Semaphore(2);
    private final ISPSenderConfiguration boot;
    private final HttpServer server;
    private final ExecutorService executor =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    r -> {
                        Thread t = new Thread(r, "ISPSender-Web");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public WebPanel(
            ConfigurationStore store,
            HealthMonitor health,
            IPInfoDatabase database,
            DailyDatabaseUpdater updater,
            ConnectionLogs logs,
            Runnable onSave)
            throws IOException {
        this.store = store;
        this.health = health;
        this.database = database;
        this.updater = updater;
        this.logs = logs;
        this.onSave = onSave;
        boot = store.current().config();
        boot.validatePanel();
        validateBind(boot.getWebBind());
        server =
                HttpServer.create(
                        new InetSocketAddress(
                                InetAddress.getByName(boot.getWebBind()), boot.getWebPort()),
                        32);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public static void validateBind(String bind) {
        validateBindRange(bind);
        try {
            InetAddress address = InetAddress.getByName(bind);
            if (!address.isLoopbackAddress() && NetworkInterface.getByInetAddress(address) == null)
                throw new IllegalArgumentException(
                        "web-bind must be loopback or an assigned local Tailscale address");
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot use web-bind", e);
        }
    }

    /**
     * Whether the address is one the panel may ever listen on. Tailscale may not be up yet, so
     * this doesn't require the address to be assigned to this host right now.
     */
    public static void validateBindRange(String bind) {
        try {
            if (!bind.equals("localhost") && !bind.matches("[0-9a-fA-F:.]+"))
                throw new IllegalArgumentException(
                        "Use a literal Tailscale IP or loopback address");
            InetAddress address = InetAddress.getByName(bind);
            if (!address.isLoopbackAddress() && !isTailnet(address))
                throw new IllegalArgumentException(
                        "web-bind must be loopback or an assigned local Tailscale address");
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot use web-bind", e);
        }
    }

    public static boolean isTailnet(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4
                ? (b[0] & 255) == 100 && (b[1] & 192) == 64
                : b.length == 16
                        && (b[0] & 255) == 0xfd
                        && (b[1] & 255) == 0x7a
                        && (b[2] & 255) == 0x11
                        && (b[3] & 255) == 0x5c
                        && (b[4] & 255) == 0xa1
                        && (b[5] & 255) == 0xe0;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders()
                    .set(
                            "Content-Security-Policy",
                            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src"
                                + " 'self'; frame-ancestors 'none'; form-action 'self'; base-uri"
                                + " 'none'");
            InetAddress remote = exchange.getRemoteAddress().getAddress();
            if (!remote.isLoopbackAddress() && !isTailnet(remote)) {
                respond(exchange, 403, Map.of("error", "Tailnet access required"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (!Set.of("GET", "POST", "PUT").contains(method)) {
                respond(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null
                    && !sameOrigin(origin, exchange.getRequestHeaders().getFirst("Host"))) {
                respond(exchange, 403, Map.of("error", "Origin rejected"));
                return;
            }
            if (path.equals("/api/login") && method.equals("POST")) {
                login(exchange, remote);
                return;
            }
            if (!path.startsWith("/api/")) {
                if (!method.equals("GET")) {
                    respond(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }
                String resource =
                        switch (path) {
                            case "/", "/index.html" -> "index.html";
                            case "/app.js" -> "app.js";
                            case "/theme.js" -> "theme.js";
                            case "/style.css" -> "style.css";
                            default -> null;
                        };
                if (resource == null) {
                    respond(exchange, 404, Map.of("error", "Not found"));
                    return;
                }
                try (InputStream stream = getClass().getResourceAsStream("/web/" + resource)) {
                    if (stream == null) {
                        respond(exchange, 404, Map.of("error", "Not found"));
                        return;
                    }
                    exchange.getResponseHeaders()
                            .set(
                                    "Content-Type",
                                    resource.endsWith("css")
                                            ? "text/css; charset=utf-8"
                                            : resource.endsWith("js")
                                                    ? "text/javascript; charset=utf-8"
                                                    : "text/html; charset=utf-8");
                    byte[] bytes = stream.readAllBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                return;
            }
            String id = cookie(exchange);
            Session session = sessions.get(id);
            if (session == null || session.expires() < System.currentTimeMillis()) {
                sessions.remove(id);
                respond(exchange, 401, Map.of("error", "Login required"));
                return;
            }
            if (!method.equals("GET")
                    && !constantEquals(
                            session.csrf(),
                            exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) {
                respond(exchange, 403, Map.of("error", "CSRF token required"));
                return;
            }
            if (path.equals("/api/state") && method.equals("GET")) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("csrf", session.csrf());
                state.put("config", store.values(true));
                state.put("servers", health.snapshot());
                state.put("databaseLoaded", database.isLoaded());
                state.put(
                        "nextDownload",
                        updater.nextUpdate() == null ? null : updater.nextUpdate().toString());
                state.put("recentLogs", logs.recent());
                state.put("tokenConfigured", store.current().config().resolveToken() != null);
                state.put("restartRequired", restartRequired());
                state.put(
                        "healthFailureThreshold",
                        store.current().config().getHealthFailureThreshold());
                state.put(
                        "discordConfigured",
                        store.current().config().resolveDiscordWebhook() != null);
                // Gson's reflection cannot access java.time internals on Java 17.
                state.put(
                        "lastDownload",
                        updater.lastSuccess() == null ? null : updater.lastSuccess().toString());
                respond(exchange, 200, state);
            } else if (path.equals("/api/config") && method.equals("PUT")) {
                Map<String, Object> changes = body(exchange);
                // Say what is wrong without echoing the URL itself.
                if (changes.get("discord-webhook-url") instanceof String webhook
                        && !webhook.isBlank()
                        && !ISPSenderConfiguration.isDiscordWebhook(webhook.trim())) {
                    respond(
                            exchange,
                            400,
                            Map.of("error", "Discord 웹훅 URL 형식이 올바르지 않습니다."));
                    return;
                }
                // A new address must work after the restart, or the panel locks itself out.
                if (changes.get("web-bind") instanceof String bind
                        && !bind.equals(store.current().config().getWebBind())) validateBind(bind);
                store.save(changes);
                onSave.run();
                respond(exchange, 200, Map.of("saved", true, "restartRequired", restartRequired()));
            } else if (path.equals("/api/logout") && method.equals("POST")) {
                sessions.remove(id);
                exchange.getResponseHeaders()
                        .add(
                                "Set-Cookie",
                                "ISPSENDER_SESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                respond(exchange, 200, Map.of("ok", true));
            } else if (path.equals("/api/logs") && method.equals("GET")) {
                Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
                LocalDate day =
                        LocalDate.parse(params.getOrDefault("date", updater.today().toString()));
                int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
                String search = params.getOrDefault("q", "");
                if (search.length() > 100) {
                    respond(exchange, 400, Map.of("error", "검색어는 100자 이하로 입력하세요."));
                    return;
                }
                respond(exchange, 200, logs.read(day, offset, 100, search));
            } else if (path.equals("/api/stats") && method.equals("GET")) {
                statistics(exchange);
            } else if (path.equals("/api/simulate") && method.equals("POST")) {
                simulate(exchange);
            } else if (path.equals("/api/check-address") && method.equals("POST")) {
                checkAddress(exchange);
            } else {
                respond(exchange, 404, Map.of("error", "Not found"));
            }
        } catch (IllegalArgumentException
                | java.time.DateTimeException
                | tools.jackson.core.JacksonException e) {
            // Client errors are deliberately concise; never echo submitted secrets or mapper
            // diagnostics.
            safeError(
                    exchange,
                    400,
                    "Invalid settings or request; check server addresses, time zone and numeric"
                        + " ranges");
        } catch (Exception e) {
            safeError(exchange, 500, "Request failed; check server storage and configuration");
        } finally {
            exchange.close();
        }
    }

    private void statistics(HttpExchange exchange) throws IOException {
        String raw = query(exchange.getRequestURI().getRawQuery()).getOrDefault("days", "7");
        int days;
        try {
            days = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            days = 0;
        }
        if (days < 1 || days > 31) {
            respond(exchange, 400, Map.of("error", "기간은 1~31일 사이로 입력하세요."));
            return;
        }
        respond(exchange, 200, logs.stats(updater.today(), days));
    }

    /** Shows the rules a connection from this address would meet, using the live health state. */
    private void simulate(HttpExchange exchange) throws IOException {
        Object value = body(exchange).get("ip");
        InetAddress address = value instanceof String text ? IpLiteral.parse(text.trim()) : null;
        if (address == null) {
            respond(exchange, 400, Map.of("error", "올바른 IP 주소를 입력하세요."));
            return;
        }
        ConfigurationStore.Snapshot snapshot = store.current();
        IPInfoRecord record = database.lookup(address);
        List<RoutingTable.Step> steps =
                snapshot.routing()
                        .explain(record, health::isAlive, snapshot.config().isFallbackEnabled());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", address.getHostAddress());
        result.put("found", record != null);
        if (record != null) {
            result.put("asn", record.asn());
            result.put("asnName", record.asName());
            result.put("country", record.country());
            result.put("countryCode", record.countryCode());
        }
        result.put("steps", steps);
        for (RoutingTable.Step step : steps) {
            if (!step.selected()) continue;
            // The first available server of the selected rule, so the answer is repeatable.
            Server server = Server.parse(step.available().get(0));
            if (server != null) {
                result.put(
                        "route",
                        Map.of(
                                "server",
                                server.toString(),
                                "target",
                                health.target(server).toString(),
                                "rule",
                                step.rule()));
            }
            break;
        }
        respond(exchange, 200, result);
    }

    private void checkAddress(HttpExchange exchange) throws IOException {
        Object value = body(exchange).get("address");
        String input = value instanceof String text ? text.trim() : "";
        Server configured = Server.parse(input);
        if (configured == null) {
            respond(exchange, 400, Map.of("error", "주소 형식이 올바르지 않습니다."));
            return;
        }
        if (!checks.tryAcquire()) {
            respond(
                    exchange,
                    429,
                    Map.of("error", "다른 확인이 진행 중입니다. 잠시 후 다시 시도하세요."));
            return;
        }
        try {
            respond(exchange, 200, inspect(input, configured));
        } finally {
            checks.release();
        }
    }

    /** Fresh DNS and probes, not the cache, so the answer matches what a transfer would use now. */
    private Map<String, Object> inspect(String input, Server configured) {
        SrvResolver resolver = health.resolver();
        int timeout = store.current().config().getHealthCheckTimeoutMillis();
        String host = configured.host();
        List<SrvResolver.Record> srv = List.of();
        List<String> cname = List.of();
        if (IpLiteral.parse(host) == null) {
            try {
                srv = resolver.queryFresh(host);
            } catch (NamingException e) {
                srv = List.of();
            }
            cname = resolver.queryCname(host);
        }
        boolean usesSrv = resolver.eligible(configured) && !srv.isEmpty();
        Server target = usesSrv ? new Server(srv.get(0).target(), srv.get(0).port()) : configured;
        HealthMonitor.Status tcp = HealthMonitor.probe(target, timeout, HealthMonitor.Mode.TCP);
        boolean reachable = tcp.alive();
        // The status ping runs only after a TCP connection, so both facts are shown.
        HealthMonitor.Status ping =
                reachable
                        ? HealthMonitor.probe(target, timeout, HealthMonitor.Mode.MINECRAFT)
                        : null;
        boolean minecraft = ping != null && ping.alive();
        String reason = !reachable ? tcp.reason() : !minecraft ? ping.reason() : null;
        long latency = minecraft ? ping.latencyMillis() : tcp.latencyMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", input);
        result.put("host", configured.host());
        result.put("port", configured.port());
        result.put("srvEligible", resolver.eligible(configured));
        result.put("srv", srv);
        result.put("cname", cname);
        result.put("addresses", addresses(target.host()));
        result.put("target", target.toString());
        result.put("usesSrv", usesSrv);
        result.put("reachable", reachable);
        result.put("minecraft", minecraft);
        result.put("latencyMillis", latency);
        if (reason != null) result.put("reason", reason);
        return result;
    }

    private static List<String> addresses(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .map(InetAddress::getHostAddress)
                    .distinct()
                    .toList();
        } catch (UnknownHostException e) {
            return List.of();
        }
    }

    private static void safeError(HttpExchange exchange, int code, String error) {
        try {
            respond(exchange, code, Map.of("error", error));
        } catch (IOException ignored) {
        }
    }

    private boolean restartRequired() {
        ISPSenderConfiguration c = store.current().config();
        return c.isWebEnabled() != boot.isWebEnabled()
                || !c.getWebBind().equals(boot.getWebBind())
                || c.getWebPort() != boot.getWebPort()
                || !Objects.equals(c.getWebPassword(), boot.getWebPassword())
                || !c.getDatabaseFile().equals(boot.getDatabaseFile());
    }

    private void login(HttpExchange exchange, InetAddress remote) throws IOException {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> e.getValue().until() < now);
        Attempts count = attempts.get(remote);
        if (count != null && count.count() >= 10) {
            respond(exchange, 429, Map.of("error", "Too many attempts; try in five minutes"));
            return;
        }
        if (attempts.size() > 1024) {
            respond(exchange, 429, Map.of("error", "Try later"));
            return;
        }
        Object value = body(exchange).get("password");
        if (!(value instanceof String password)
                || !constantEquals(boot.resolveWebPassword(), password)) {
            attempts.compute(
                    remote,
                    (key, old) ->
                            new Attempts(
                                    old == null ? 1 : old.count() + 1,
                                    old == null ? now + 300_000 : old.until()));
            respond(exchange, 401, Map.of("error", "Incorrect password"));
            return;
        }
        attempts.remove(remote);
        sessions.entrySet().removeIf(e -> e.getValue().expires() < now);
        if (sessions.size() >= 128) {
            respond(exchange, 429, Map.of("error", "Session limit reached"));
            return;
        }
        String id = randomToken();
        Session session = new Session(randomToken(), now + SESSION_MS);
        sessions.put(id, session);
        boolean secure =
                Optional.ofNullable(exchange.getRequestHeaders().getFirst("Origin"))
                        .orElse("")
                        .startsWith("https://");
        exchange.getResponseHeaders()
                .add(
                        "Set-Cookie",
                        "ISPSENDER_SESSION="
                                + id
                                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200"
                                + (secure ? "; Secure" : ""));
        respond(exchange, 200, Map.of("csrf", session.csrf()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new IllegalArgumentException("JSON required");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new IllegalArgumentException("Request too large");
        Map<String, Object> result = JSON_READER.readValue(bytes, Map.class);
        if (result == null) throw new IllegalArgumentException("JSON object required");
        return result;
    }

    private static boolean sameOrigin(String origin, String host) {
        try {
            URI uri = URI.create(origin);
            return Set.of("http", "https").contains(uri.getScheme())
                    && Objects.equals(uri.getRawAuthority(), host);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(
                    digest.digest(a.getBytes(StandardCharsets.UTF_8)),
                    digest.digest(b.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cookie(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String value = part.trim();
                if (!value.startsWith("ISPSENDER_SESSION=")) continue;
                value = value.substring("ISPSENDER_SESSION=".length());
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);
                return value;
            }
        }
        return "";
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw != null)
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2)
                    result.put(
                            URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        return result;
    }

    private static void respond(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        sessions.clear();
    }
}
