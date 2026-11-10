package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.dns.SrvResolver;

import org.junit.jupiter.api.Test;

import javax.naming.ServiceUnavailableException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class SrvResolverTest {
    private static final Server SRV_ONLY = new Server("srv-only.test", 25565);

    @Test
    void choosesLowestPriorityThenHighestWeightThenSmallestTarget() throws Exception {
        var resolver =
                new SrvResolver(
                        name ->
                                List.of(
                                        new SrvResolver.Record(10, 100, 25565, "backup.example."),
                                        new SrvResolver.Record(5, 1, 25566, "light.example."),
                                        new SrvResolver.Record(5, 9, 25567, "heavy.example."),
                                        new SrvResolver.Record(5, 9, 25565, "alpha.example.")),
                        () -> 0L);
        assertEquals(new Server("alpha.example", 25565), resolver.resolve(SRV_ONLY));
        assertTrue(resolver.isSrv(SRV_ONLY));
        assertEquals(
                List.of(
                        new SrvResolver.Record(5, 9, 25565, "alpha.example"),
                        new SrvResolver.Record(5, 9, 25567, "heavy.example"),
                        new SrvResolver.Record(5, 1, 25566, "light.example"),
                        new SrvResolver.Record(10, 100, 25565, "backup.example")),
                resolver.queryFresh("example.test"));
    }

    @Test
    void dotTargetsBadPortsAndBadNamesAreDropped() {
        var unusable =
                new SrvResolver(
                        name ->
                                List.of(
                                        new SrvResolver.Record(0, 0, 25565, "."),
                                        new SrvResolver.Record(0, 0, 0, "port-zero.example."),
                                        new SrvResolver.Record(0, 0, 70000, "port-large.example."),
                                        new SrvResolver.Record(0, 0, 25565, "bad name.example.")),
                        () -> 0L);
        assertEquals(SRV_ONLY, unusable.resolve(SRV_ONLY));
        assertFalse(unusable.isSrv(SRV_ONLY));
        var trailingDot =
                new SrvResolver(
                        name -> List.of(new SrvResolver.Record(0, 0, 25565, "real.example.")),
                        () -> 0L);
        assertEquals(new Server("real.example", 25565), trailingDot.resolve(SRV_ONLY));
        assertTrue(trailingDot.isSrv(SRV_ONLY));
    }

    @Test
    void ineligibleNamesNeverQueryDns() {
        AtomicInteger queries = new AtomicInteger();
        var resolver =
                new SrvResolver(
                        name -> {
                            queries.incrementAndGet();
                            return List.of(new SrvResolver.Record(0, 0, 25565, "x.example."));
                        },
                        () -> 0L);
        for (Server server :
                List.of(
                        new Server("localhost", 25565),
                        new Server("host.example", 25566),
                        new Server("1.2.3.4", 25565),
                        new Server("2001:db8::1", 25565))) {
            assertFalse(resolver.eligible(server), server.toString());
            assertEquals(server, resolver.resolve(server));
        }
        assertEquals(0, queries.get());
        assertTrue(resolver.eligible(new Server("host.example", 25565)));
    }

    @Test
    void jndiPathReadsSrvAndTreatsNxdomainAsNoSrv() throws Exception {
        try (var dns = new StubDns("_minecraft._tcp.srv-only.test", "localhost", 25999)) {
            String previous = System.getProperty("ispsender.dns.server");
            System.setProperty("ispsender.dns.server", "dns://127.0.0.1:" + dns.port());
            try {
                var resolver = new SrvResolver();
                assertEquals(new Server("localhost", 25999), resolver.resolve(SRV_ONLY));
                assertTrue(resolver.isSrv(SRV_ONLY));
                Server unknown = new Server("nothing.test", 25565);
                assertEquals(unknown, resolver.resolve(unknown));
                assertFalse(resolver.isSrv(unknown));
                assertEquals(List.of(), resolver.queryCname("srv-only.test"));
            } finally {
                if (previous == null) {
                    System.clearProperty("ispsender.dns.server");
                } else {
                    System.setProperty("ispsender.dns.server", previous);
                }
            }
        }
    }

    @Test
    void transientFailureKeepsLastKnownResultAndRetriesOnTheNextProbe() {
        AtomicLong now = new AtomicLong();
        AtomicInteger queries = new AtomicInteger();
        var resolver =
                new SrvResolver(
                        name -> {
                            int call = queries.incrementAndGet();
                            if (call == 1) {
                                return List.of(new SrvResolver.Record(0, 0, 25001, "first.example."));
                            }
                            if (call == 2) {
                                throw new ServiceUnavailableException("dns timeout");
                            }
                            return List.of(new SrvResolver.Record(0, 0, 25002, "second.example."));
                        },
                        now::get);
        assertEquals(new Server("first.example", 25001), resolver.resolve(SRV_ONLY));
        now.set(60_000L);
        assertEquals(new Server("first.example", 25001), resolver.resolve(SRV_ONLY));
        assertEquals(2, queries.get());
        // The failed lookup did not restart the TTL, so the next probe asks again at once.
        assertEquals(new Server("second.example", 25002), resolver.resolve(SRV_ONLY));
        assertEquals(3, queries.get());
    }

    @Test
    void cacheServesOneMinuteThenRefreshesAndRetainForgetsRemovedServers() {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicInteger queries = new AtomicInteger();
        var resolver =
                new SrvResolver(
                        name ->
                                queries.incrementAndGet() == 1
                                        ? List.of(new SrvResolver.Record(0, 0, 25001, "first.example."))
                                        : List.of(new SrvResolver.Record(0, 0, 25002, "second.example.")),
                        now::get);
        assertEquals(new Server("first.example", 25001), resolver.resolve(SRV_ONLY));
        now.set(60_999L);
        assertEquals(new Server("first.example", 25001), resolver.resolve(SRV_ONLY));
        assertEquals(1, queries.get());
        now.set(61_000L);
        assertEquals(new Server("second.example", 25002), resolver.resolve(SRV_ONLY));
        assertEquals(2, queries.get());
        assertEquals(new Server("second.example", 25002), resolver.cached(SRV_ONLY));
        resolver.retain(Set.of());
        assertEquals(SRV_ONLY, resolver.cached(SRV_ONLY));
        assertFalse(resolver.isSrv(SRV_ONLY));
    }

    @Test
    void parseRecordAcceptsOnlyFourWellFormedFields() {
        assertEquals(
                new SrvResolver.Record(1, 2, 25565, "mc.example."),
                SrvResolver.parseRecord("1 2 25565 mc.example."));
        for (String value :
                List.of(
                        "",
                        "1 2 25565",
                        "1 2 x mc.example.",
                        "70000 1 25565 mc.example.",
                        "-1 1 25565 mc.example.",
                        "1 2 3 4 5")) {
            assertNull(SrvResolver.parseRecord(value), value);
        }
    }

    /** A loopback DNS server: SRV for one name, no data for other types, NXDOMAIN otherwise. */
    static final class StubDns implements AutoCloseable {
        private final DatagramSocket socket;
        private final String srvName;
        private final String target;
        private final int port;
        private final Thread thread;

        StubDns(String srvName, String target, int port) throws IOException {
            this.srvName = srvName;
            this.target = target;
            this.port = port;
            this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "stub-dns");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            byte[] buffer = new byte[512];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    byte[] reply =
                            answer(Arrays.copyOf(packet.getData(), packet.getLength()));
                    if (reply != null) {
                        socket.send(
                                new DatagramPacket(
                                        reply, reply.length, packet.getSocketAddress()));
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        /** The response to one question, or null when the request is too short to parse. */
        byte[] answer(byte[] request) {
            if (request.length < 12) return null;
            int position = 12;
            StringBuilder name = new StringBuilder();
            while (position < request.length && request[position] != 0) {
                int length = request[position] & 0xFF;
                if (position + 1 + length > request.length) return null;
                if (name.length() > 0) name.append('.');
                name.append(new String(request, position + 1, length, StandardCharsets.US_ASCII));
                position += 1 + length;
            }
            if (position + 5 > request.length) return null;
            int type = ((request[position + 1] & 0xFF) << 8) | (request[position + 2] & 0xFF);
            int questionEnd = position + 5;
            boolean known = name.toString().equalsIgnoreCase(srvName);
            boolean srv = known && type == 33;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(request, 0, 2);
            writeShort(out, known ? 0x8400 : 0x8403);
            writeShort(out, 1);
            writeShort(out, srv ? 1 : 0);
            writeShort(out, 0);
            writeShort(out, 0);
            out.write(request, 12, questionEnd - 12);
            if (srv) {
                ByteArrayOutputStream rdata = new ByteArrayOutputStream();
                writeShort(rdata, 0);
                writeShort(rdata, 0);
                writeShort(rdata, port);
                for (String label : target.split("\\.")) {
                    rdata.write(label.length());
                    rdata.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
                }
                rdata.write(0);
                out.write(0xC0);
                out.write(0x0C);
                writeShort(out, 33);
                writeShort(out, 1);
                writeShort(out, 0);
                writeShort(out, 60);
                writeShort(out, rdata.size());
                out.writeBytes(rdata.toByteArray());
            }
            return out.toByteArray();
        }

        private static void writeShort(ByteArrayOutputStream out, int value) {
            out.write(value >> 8);
            out.write(value);
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
