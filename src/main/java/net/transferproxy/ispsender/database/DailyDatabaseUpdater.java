package net.transferproxy.ispsender.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One successful download per calendar day, including catch-up after downtime. */
public final class DailyDatabaseUpdater implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DailyDatabaseUpdater.class);
    private final Path databasePath;
    private final Path stamp;
    private final Clock clock;
    private final Supplier<ZoneId> zone;
    private final BooleanSupplier download;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "ISPSender-Database");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile LocalDate lastSuccess;
    private volatile Instant nextUpdate;
    private ScheduledFuture<?> scheduled;

    public DailyDatabaseUpdater(
            Path databasePath, Clock clock, Supplier<ZoneId> zone, BooleanSupplier download) {
        this.databasePath = databasePath;
        this.stamp = databasePath.resolveSibling(databasePath.getFileName() + ".download-date");
        this.clock = clock;
        this.zone = zone;
        this.download = download;
        try {
            lastSuccess = LocalDate.parse(Files.readString(stamp).trim());
        } catch (Exception ignored) {
        }
    }

    public void invalidate() {
        lastSuccess = null;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone.get()));
    }

    public boolean isDue() {
        return !Files.isRegularFile(databasePath) || !today().equals(lastSuccess);
    }

    public synchronized boolean updateIfDue() {
        if (!isDue()) return false;
        LocalDate day = today();
        if (!download.getAsBoolean()) return false;
        try {
            Path temp = stamp.resolveSibling(stamp.getFileName() + ".tmp");
            Files.writeString(temp, day.toString());
            try {
                Files.move(
                        temp,
                        stamp,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, stamp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Cannot persist database download date", e);
        }
        lastSuccess = day;
        return true;
    }

    public void start() {
        executor.execute(this::runAndSchedule);
    }

    public void checkSoon() {
        if (!executor.isShutdown()) executor.execute(this::runAndSchedule);
    }

    private void runAndSchedule() {
        if (scheduled != null) scheduled.cancel(false);
        try {
            updateIfDue();
        } catch (Exception e) {
            LOGGER.error("Daily database update failed", e);
        }
        if (executor.isShutdown()) return;
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone.get()));
        Instant midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone.get()).toInstant();
        // Failed downloads retry without marking today's database as complete.
        nextUpdate = isDue() ? now.toInstant().plusSeconds(300) : midnight;
        long delay = Math.max(1, Duration.between(clock.instant(), nextUpdate).toMillis());
        try {
            scheduled = executor.schedule(this::runAndSchedule, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    public LocalDate lastSuccess() {
        return lastSuccess;
    }

    public Instant nextUpdate() {
        return nextUpdate;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
