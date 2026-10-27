package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.health.HealthMonitor;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.util.Set;

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

    static void await(java.util.function.BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(done.getAsBoolean());
    }
}
