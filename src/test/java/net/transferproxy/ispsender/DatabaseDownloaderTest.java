package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.database.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

class DatabaseDownloaderTest {
    @TempDir Path dir;

    @Test
    void validatesAndInstallsDownloadsButPreservesWorkingCacheOnFailure() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/mmdb/ipv4-24.mmdb")) {
            fixture = input.readAllBytes();
        }
        AtomicReference<byte[]> reply = new AtomicReference<>(fixture);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/db",
                exchange -> {
                    try (exchange) {
                        byte[] data = reply.get();
                        exchange.sendResponseHeaders(200, data.length);
                        exchange.getResponseBody().write(data);
                    }
                });
        server.start();
        try (var database = new IPInfoDatabase()) {
            var config = new ISPSenderConfiguration();
            config.setToken("test-token");
            config.setDatabaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/db?token={token}");
            Path target = dir.resolve("db.mmdb");
            var downloader = new DatabaseDownloader(config, target);
            assertTrue(downloader.download(database));
            assertTrue(database.isLoaded());
            assertArrayEquals(fixture, Files.readAllBytes(target));
            reply.set("invalid database".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(downloader.download(database));
            assertTrue(database.isLoaded());
            assertArrayEquals(fixture, Files.readAllBytes(target));
            assertFalse(Files.exists(dir.resolve("db.mmdb.tmp")));
            reply.set(fixture);
            assertTrue(downloader.download(database));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledBodyFailsInsteadOfBlockingTheUpdaterThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(
                "/db",
                exchange -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(200, 1000);
                        exchange.getResponseBody().write(new byte[10]);
                        exchange.getResponseBody().flush();
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        try (var database = new IPInfoDatabase()) {
            var config = new ISPSenderConfiguration();
            config.setToken("test-token");
            config.setReadTimeoutSeconds(1);
            config.setDatabaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/db?token={token}");
            var downloader = new DatabaseDownloader(config, dir.resolve("stalled.mmdb"));
            assertFalse(
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(15), () -> downloader.download(database)));
            assertFalse(Files.exists(dir.resolve("stalled.mmdb")));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
