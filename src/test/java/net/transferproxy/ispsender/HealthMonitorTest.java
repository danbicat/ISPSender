package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.dns.SrvResolver;
import net.transferproxy.ispsender.health.HealthMonitor;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

class HealthMonitorTest {
    @Test
    void actualSocketAvailabilityAndRecoveryUpdateRoutingEligibility() throws Exception {
        ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Server target = new Server("127.0.0.1", listener.getLocalPort());
        try (var monitor = new HealthMonitor(() -> Set.of(target), () -> 200)) {
            assertFalse(monitor.isAlive(target));
            monitor.refresh();
            await(() -> monitor.isAlive(target));
            assertTrue(monitor.snapshot().get(0).alive());
            listener.close();
            monitor.refresh();
            await(() -> !monitor.isAlive(target));
            try (ServerSocket recovered =
                    new ServerSocket(target.port(), 50, InetAddress.getLoopbackAddress())) {
                monitor.refresh();
                await(() -> monitor.isAlive(target));
            }
        } finally {
            listener.close();
        }
    }

    @Test
    void srvTargetIsProbedAndExposedByTargetAndSnapshot() throws Exception {
        ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        int port = listener.getLocalPort();
        Server configured = new Server("srv-only.test", 25565);
        var resolver =
                new SrvResolver(name -> List.of(new SrvResolver.Record(0, 0, port, "localhost.")), () -> 0L);
        try (var monitor =
                new HealthMonitor(
                        () -> Set.of(configured),
                        () -> 500,
                        () -> HealthMonitor.Mode.TCP,
                        () -> 1,
                        resolver,
                        HealthMonitor.Listener.NONE)) {
            refreshUntil(monitor, () -> monitor.isAlive(configured));
            assertEquals(new Server("localhost", port), monitor.target(configured));
            var status = monitor.snapshot().get(0);
            assertEquals(configured.toString(), status.server());
            assertEquals("localhost:" + port, status.target());
            assertTrue(status.srv());
        } finally {
            listener.close();
        }
    }

    @Test
    void minecraftPingAcceptsAStatusReplyAndRejectsOtherProtocols() throws Exception {
        try (ServerSocket status = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            serve(status, statusReply());
            var ok =
                    HealthMonitor.probe(
                            new Server("127.0.0.1", status.getLocalPort()),
                            1000,
                            HealthMonitor.Mode.MINECRAFT);
            assertTrue(ok.alive(), String.valueOf(ok.reason()));
            assertNull(ok.reason());
        }
        try (ServerSocket http = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            serve(http, "HTTP/1.0 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            var bad =
                    HealthMonitor.probe(
                            new Server("127.0.0.1", http.getLocalPort()),
                            1000,
                            HealthMonitor.Mode.MINECRAFT);
            assertFalse(bad.alive());
            assertEquals(HealthMonitor.BAD_RESPONSE, bad.reason());
        }
    }

    @Test
    void silentOpenPortPassesTcpButFailsTheMinecraftPingWithinTheTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            // Connections complete through the backlog and are never answered.
            Server target = new Server("127.0.0.1", silent.getLocalPort());
            assertTrue(HealthMonitor.probe(target, 300, HealthMonitor.Mode.TCP).alive());
            long start = System.nanoTime();
            var ping = HealthMonitor.probe(target, 300, HealthMonitor.Mode.MINECRAFT);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertFalse(ping.alive());
            assertEquals(HealthMonitor.TIMEOUT, ping.reason());
            // One deadline covers connect and ping: the probe ends near its 300 ms budget.
            assertTrue(elapsedMillis < 450, "ping took " + elapsedMillis + " ms");
        }
    }

    @Test
    void refusedAndUnresolvableAddressesHaveTheirOwnReasons() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        assertEquals(
                HealthMonitor.REFUSED,
                HealthMonitor.probe(new Server("127.0.0.1", closed), 500).reason());
        assertEquals(
                HealthMonitor.DNS,
                HealthMonitor.probe(new Server("no-such-host.invalid", 25565), 500).reason());
    }

    @Test
    void failureThresholdKeepsAServerAliveUntilConsecutiveFailuresReachIt() throws Exception {
        ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Server target = new Server("127.0.0.1", listener.getLocalPort());
        try (var monitor =
                new HealthMonitor(
                        () -> Set.of(target),
                        () -> 200,
                        () -> HealthMonitor.Mode.TCP,
                        () -> 3,
                        new SrvResolver(),
                        HealthMonitor.Listener.NONE)) {
            refreshUntil(monitor, () -> monitor.isAlive(target));
            listener.close();
            refreshUntil(monitor, () -> monitor.snapshot().get(0).failures() == 1);
            assertTrue(monitor.snapshot().get(0).alive());
            refreshUntil(monitor, () -> monitor.snapshot().get(0).failures() == 2);
            assertTrue(monitor.snapshot().get(0).alive());
            refreshUntil(monitor, () -> monitor.snapshot().get(0).failures() == 3);
            assertFalse(monitor.snapshot().get(0).alive());
            assertEquals(HealthMonitor.REFUSED, monitor.snapshot().get(0).reason());
        } finally {
            listener.close();
        }
    }

    @Test
    void listenerFiresOncePerFlipAndNeverOnTheFirstObservation() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Server target = new Server("127.0.0.1", port);
        List<String> events = new CopyOnWriteArrayList<>();
        try (var monitor =
                new HealthMonitor(
                        () -> Set.of(target),
                        () -> 200,
                        () -> HealthMonitor.Mode.TCP,
                        () -> 1,
                        new SrvResolver(),
                        (server, alive, reason) -> events.add(alive + ":" + reason))) {
            refreshUntil(monitor, () -> monitor.snapshot().get(0).failures() == 1);
            assertEquals(List.of(), events);
            try (ServerSocket listener =
                    new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
                refreshUntil(monitor, () -> monitor.isAlive(target));
                refreshUntil(monitor, () -> events.size() == 1);
                for (int i = 0; i < 3; i++) {
                    monitor.refresh();
                    Thread.sleep(50);
                }
                assertEquals(List.of("true:null"), events);
            }
            refreshUntil(monitor, () -> !monitor.isAlive(target));
            refreshUntil(monitor, () -> events.size() == 2);
            assertEquals(List.of("true:null", "false:REFUSED"), events);
        }
    }

    /** Refreshes until the condition holds, since each refresh probes asynchronously. */
    static void refreshUntil(HealthMonitor monitor, BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < end) {
            monitor.refresh();
            Thread.sleep(50);
        }
        assertTrue(done.getAsBoolean());
    }

    /** Answers one connection the way a Minecraft server does, after reading the two requests. */
    static void serve(ServerSocket server, byte[] reply) {
        Thread thread =
                new Thread(
                        () -> {
                            try (Socket client = server.accept()) {
                                InputStream in = client.getInputStream();
                                readFrame(in);
                                readFrame(in);
                                client.getOutputStream().write(reply);
                                client.getOutputStream().flush();
                            } catch (IOException ignored) {
                                /* The test asserts on the probe result, not on this thread. */
                            }
                        });
        thread.setDaemon(true);
        thread.start();
    }

    static byte[] statusReply() throws IOException {
        byte[] json =
                "{\"version\":{\"name\":\"test\",\"protocol\":769},\"players\":{\"max\":1,\"online\":0}}"
                        .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0);
        writeVarInt(body, json.length);
        body.write(json);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, body.size());
        body.writeTo(frame);
        return frame.toByteArray();
    }

    /** Answers every connection like a Minecraft server does, until the socket closes. */
    static void serveForever(ServerSocket server, byte[] reply) {
        Thread thread =
                new Thread(
                        () -> {
                            while (!server.isClosed()) {
                                try (Socket client = server.accept()) {
                                    InputStream in = client.getInputStream();
                                    readFrame(in);
                                    readFrame(in);
                                    client.getOutputStream().write(reply);
                                    client.getOutputStream().flush();
                                } catch (IOException ignored) {
                                    /* A probe that gave up, or the server closing. */
                                }
                            }
                        });
        thread.setDaemon(true);
        thread.start();
    }

    static void readFrame(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (in.readNBytes(length).length != length) throw new EOFException();
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }

    static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    static void await(java.util.function.BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(done.getAsBoolean());
    }
}
