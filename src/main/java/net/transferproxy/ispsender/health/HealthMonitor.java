package net.transferproxy.ispsender.health;

import net.transferproxy.ispsender.data.Server;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** TCP probes run off the player event loop and independently of database downloads. */
public final class HealthMonitor implements AutoCloseable {
    public static final int INTERVAL_SECONDS = 10;
    private final Supplier<Set<Server>> targets;
    private final IntSupplier timeout;
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

    public record Status(String server, boolean alive, long checkedAt, long latencyMillis) {}

    public HealthMonitor(Supplier<Set<Server>> targets, IntSupplier timeout) {
        this.targets = targets;
        this.timeout = timeout;
    }

    public void start() {
        timer.scheduleAtFixedRate(this::refresh, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void refresh() {
        Set<Server> current = targets.get();
        states.keySet().retainAll(current);
        for (Server server : current) {
            if (!pending.add(server)) continue;
            try {
                probes.execute(
                        () -> {
                            try {
                                states.put(server, probe(server, timeout.getAsInt()));
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
                && System.currentTimeMillis() - status.checkedAt() < 30_000;
    }

    public List<Status> snapshot() {
        return targets.get().stream()
                .sorted(Comparator.comparing(Server::toString))
                .map(
                        server -> {
                            Status status = states.get(server);
                            return status == null
                                    ? new Status(server.toString(), false, 0, -1)
                                    : new Status(
                                            server.toString(),
                                            isAlive(server),
                                            status.checkedAt(),
                                            status.latencyMillis());
                        })
                .toList();
    }

    public static Status probe(Server server, int timeoutMillis) {
        long start = System.nanoTime();
        boolean alive;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(server.host(), server.port()), timeoutMillis);
            alive = true;
        } catch (Exception e) {
            alive = false;
        }
        return new Status(
                server.toString(),
                alive,
                Instant.now().toEpochMilli(),
                alive ? (System.nanoTime() - start) / 1_000_000 : -1);
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
