package net.transferproxy.ispsender.health;

import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.dns.SrvResolver;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Probes run off the player event loop and independently of database downloads. */
public final class HealthMonitor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthMonitor.class);
    public static final int INTERVAL_SECONDS = 10;
    private static final long STALE_MILLIS = 30_000L;
    private static final int PROTOCOL_VERSION = 769;

    /** Failure reasons, shown in the panel and in Discord alerts. */
    public static final String DNS = "DNS";
    public static final String REFUSED = "REFUSED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String UNREACHABLE = "UNREACHABLE";
    public static final String BAD_RESPONSE = "BAD_RESPONSE";

    /** TCP only checks that the port accepts connections. MINECRAFT also reads a status reply. */
    public enum Mode {
        TCP,
        MINECRAFT
    }

    /** Called when a server that was known alive turns dead, or the reverse. */
    public interface Listener {
        Listener NONE = (server, alive, reason) -> {};

        void changed(Server server, boolean alive, String reason);
    }

    /** The reason is null after a successful probe. Gson omits the null fields. */
    public record Status(
            String server,
            boolean alive,
            long checkedAt,
            long latencyMillis,
            String target,
            boolean srv,
            String reason,
            int failures) {}

    private final Supplier<Set<Server>> targets;
    private final IntSupplier timeout;
    private final Supplier<Mode> mode;
    private final IntSupplier failureThreshold;
    private final SrvResolver resolver;
    private final Listener listener;
    private final ConcurrentMap<Server, Status> states = new ConcurrentHashMap<>();
    private final Set<Server> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ISPSender-HealthTimer"));
    private final ExecutorService probes =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4096),
                    r -> daemon(r, "ISPSender-Probe"),
                    new ThreadPoolExecutor.AbortPolicy());

    public HealthMonitor(Supplier<Set<Server>> targets, IntSupplier timeout) {
        this(targets, timeout, () -> Mode.TCP, () -> 1, new SrvResolver(), Listener.NONE);
    }

    public HealthMonitor(
            Supplier<Set<Server>> targets,
            IntSupplier timeout,
            Supplier<Mode> mode,
            IntSupplier failureThreshold,
            SrvResolver resolver,
            Listener listener) {
        this.targets = targets;
        this.timeout = timeout;
        this.mode = mode;
        this.failureThreshold = failureThreshold;
        this.resolver = resolver;
        this.listener = listener == null ? Listener.NONE : listener;
    }

    public void start() {
        timer.scheduleAtFixedRate(this::refresh, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void refresh() {
        Set<Server> current = targets.get();
        states.keySet().retainAll(current);
        resolver.retain(current);
        for (Server server : current) {
            if (!pending.add(server)) continue;
            try {
                probes.execute(
                        () -> {
                            try {
                                check(server);
                            } catch (RuntimeException e) {
                                LOGGER.warn(
                                        "Health check failed unexpectedly ({})",
                                        e.getClass().getSimpleName());
                            } finally {
                                pending.remove(server);
                            }
                        });
            } catch (RejectedExecutionException e) {
                pending.remove(server);
            }
        }
    }

    public boolean isAlive(Server server) {
        Status status = states.get(server);
        return status != null
                && status.alive()
                && System.currentTimeMillis() - status.checkedAt() < STALE_MILLIS;
    }

    /** The address to transfer to: the SRV target when one is cached. Never does DNS. */
    public Server target(Server configured) {
        return resolver.cached(configured);
    }

    public SrvResolver resolver() {
        return resolver;
    }

    public List<Status> snapshot() {
        return targets.get().stream()
                .sorted(Comparator.comparing(Server::toString))
                .map(
                        server -> {
                            Status status = states.get(server);
                            String target = resolver.cached(server).toString();
                            boolean srv = resolver.isSrv(server);
                            if (status == null) {
                                return new Status(
                                        server.toString(), false, 0, -1, target, srv, null, 0);
                            }
                            return new Status(
                                    server.toString(),
                                    isAlive(server),
                                    status.checkedAt(),
                                    status.latencyMillis(),
                                    target,
                                    srv,
                                    status.reason(),
                                    status.failures());
                        })
                .toList();
    }

    /** A TCP connection probe. */
    public static Status probe(Server server, int timeoutMillis) {
        return probe(server, timeoutMillis, Mode.TCP);
    }

    /**
     * One probe of an address that has already been resolved. Ping mode also sends a Minecraft
     * status request and checks the start of the reply.
     */
    public static Status probe(Server target, int timeoutMillis, Mode mode) {
        long start = System.nanoTime();
        String reason = null;
        try (Socket socket = new Socket()) {
            InetSocketAddress address = new InetSocketAddress(target.host(), target.port());
            if (address.isUnresolved()) {
                reason = DNS;
            } else {
                // The connect and the status ping share this deadline, so a probe takes about
                // timeoutMillis in total rather than one timeout per step.
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                socket.connect(address, timeoutMillis);
                if (mode == Mode.MINECRAFT) reason = ping(socket, target, deadline);
            }
        } catch (ConnectException e) {
            reason = REFUSED;
        } catch (SocketTimeoutException e) {
            reason = TIMEOUT;
        } catch (UnknownHostException e) {
            reason = DNS;
        } catch (IOException e) {
            reason = UNREACHABLE;
        }
        boolean alive = reason == null;
        return new Status(
                target.toString(),
                alive,
                Instant.now().toEpochMilli(),
                alive ? (System.nanoTime() - start) / 1_000_000 : -1,
                target.toString(),
                false,
                reason,
                0);
    }

    private void check(Server configured) {
        Server target = resolver.resolve(configured);
        Status probed = probe(target, timeout.getAsInt(), mode.get());
        record(configured, target, !target.equals(configured), probed);
    }

    /** Applies the soft-failure rule and reports a flip of a server that was known alive or dead. */
    private void record(Server configured, Server target, boolean srv, Status probed) {
        int threshold = failureThreshold.getAsInt();
        AtomicReference<Status> previous = new AtomicReference<>();
        Status current =
                states.compute(
                        configured,
                        (key, old) -> {
                            previous.set(old);
                            if (probed.alive()) {
                                return status(
                                        configured,
                                        target,
                                        srv,
                                        true,
                                        probed.checkedAt(),
                                        probed.latencyMillis(),
                                        null,
                                        0);
                            }
                            int failures = old == null ? 1 : old.failures() + 1;
                            boolean stillAlive = old != null && old.alive() && failures < threshold;
                            return status(
                                    configured,
                                    target,
                                    srv,
                                    stillAlive,
                                    probed.checkedAt(),
                                    -1,
                                    probed.reason(),
                                    failures);
                        });
        Status before = previous.get();
        if (before != null && before.alive() != current.alive()) {
            try {
                listener.changed(configured, current.alive(), current.reason());
            } catch (RuntimeException e) {
                LOGGER.warn("Health listener failed ({})", e.getClass().getSimpleName());
            }
        }
    }

    private static Status status(
            Server configured,
            Server target,
            boolean srv,
            boolean alive,
            long checkedAt,
            long latencyMillis,
            @Nullable String reason,
            int failures) {
        return new Status(
                configured.toString(),
                alive,
                checkedAt,
                latencyMillis,
                target.toString(),
                srv,
                reason,
                failures);
    }

    /**
     * Sends a handshake and a status request, then checks the start of the status reply. Reports
     * TIMEOUT once the deadline, a {@link System#nanoTime()} value, has passed.
     */
    private static @Nullable String ping(Socket socket, Server target, long deadline) {
        try {
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, PROTOCOL_VERSION);
            byte[] host = target.host().getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, host.length);
            handshake.write(host);
            handshake.write(target.port() >>> 8);
            handshake.write(target.port());
            writeVarInt(handshake, 1);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            writeFrame(request, handshake.toByteArray());
            writeFrame(request, new byte[] {0x00});
            budget(socket, deadline);
            OutputStream out = socket.getOutputStream();
            out.write(request.toByteArray());
            out.flush();

            InputStream in = socket.getInputStream();
            int length = readVarInt(socket, in, deadline);
            if (length <= 0 || readVarInt(socket, in, deadline) != 0) return BAD_RESPONSE;
            int stringLength = readVarInt(socket, in, deadline);
            if (stringLength <= 0) return BAD_RESPONSE;
            // The JSON body is not read: favicons make it large.
            return readByte(socket, in, deadline) == '{' ? null : BAD_RESPONSE;
        } catch (SocketTimeoutException e) {
            return TIMEOUT;
        } catch (IOException e) {
            return BAD_RESPONSE;
        }
    }

    /** Fails once the whole ping has used up its time, so reads cannot extend it. */
    private static void budget(Socket socket, long deadline) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new SocketTimeoutException("Minecraft status timed out");
        socket.setSoTimeout((int) remaining);
    }

    private static int readByte(Socket socket, InputStream in, long deadline) throws IOException {
        budget(socket, deadline);
        int value = in.read();
        if (value < 0) throw new EOFException("Minecraft status ended early");
        return value;
    }

    private static int readVarInt(Socket socket, InputStream in, long deadline)
            throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = readByte(socket, in, deadline);
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt is too long");
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        probes.shutdownNow();
        states.clear();
    }
}
