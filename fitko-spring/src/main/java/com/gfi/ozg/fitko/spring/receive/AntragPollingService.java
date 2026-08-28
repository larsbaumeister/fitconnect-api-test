package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Repeatedly polls one or more destinations for available submissions and
 * hands each to a {@link SubmissionProcessor}. Runs on its own single-thread
 * scheduler (not the application's {@code TaskScheduler}/{@code @Scheduled}
 * infrastructure), started and stopped along with the Spring lifecycle.
 *
 * <p>Each {@link ReceivingDestination} carries its own {@link
 * dev.fitko.fitconnect.client.SubscriberClient} - a FIT-Connect Zustellpunkt
 * is registered with its own signing/decryption keys, and the SDK bakes
 * exactly one key set into each client instance, so two destinations with
 * different keys need two separate clients even though one {@code
 * AntragPollingService} still polls both. Destinations are polled one after
 * another within a single cycle; a failure polling one (network blip,
 * expired token, ...) is logged and does not stop the others in the same
 * cycle from being polled.
 *
 * <p>Polling and the callback webhook endpoint (see {@code
 * FitConnectCallbackController}) are independent and not mutually exclusive:
 * a destination with {@code fitconnect.receiver.callback.enabled} and its
 * own {@code callback-secret} configured is still polled here too - a missed
 * or failed callback delivery is simply picked up on the next poll cycle
 * instead of being lost.
 *
 * <p>Per-cycle timings and counts are reported to {@link ReceivePipelineMetrics}
 * (a no-op unless Micrometer is on the classpath), and the timestamp of the
 * last successful poll per destination is kept for {@code
 * FitConnectReceiverHealthIndicator} - together these let an operator tell
 * "polling is healthy but idle" from "polling has been failing".
 */
@Slf4j
public class AntragPollingService implements SmartLifecycle {

    private final List<ReceivingDestination> destinations;
    private final SubmissionProcessor submissionProcessor;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ReceivePipelineMetrics metrics;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentMap<UUID, Instant> lastSuccessfulPollByDestination = new ConcurrentHashMap<>();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile Instant startedAt;

    public AntragPollingService(List<ReceivingDestination> destinations, SubmissionProcessor submissionProcessor,
                                 FitConnectProperties.Receiver receiverProperties, ReceivePipelineMetrics metrics) {
        this.destinations = List.copyOf(Objects.requireNonNull(destinations,
                "fitconnect.receiver.destinations must be set to poll for submissions"));
        if (this.destinations.isEmpty()) {
            throw new IllegalArgumentException(
                    "fitconnect.receiver.destinations must contain at least one destination");
        }
        this.submissionProcessor = Objects.requireNonNull(submissionProcessor, "submissionProcessor must not be null");
        this.receiverProperties = Objects.requireNonNull(receiverProperties, "receiverProperties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(AntragPollingService::newDaemonThread);
    }

    @Override
    public void start() {
        if (scheduledTask != null) {
            return;
        }
        Duration initialDelay = receiverProperties.getPolling().getInitialDelay();
        Duration interval = receiverProperties.getPolling().getInterval();
        startedAt = Instant.now();
        scheduledTask = scheduler.scheduleWithFixedDelay(
                this::pollSafely, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("FIT-Connect polling started for destinations {} (every {})", destinationIds(), interval);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> task = scheduledTask;
        scheduledTask = null;
        if (task != null) {
            task.cancel(false);
            log.info("FIT-Connect polling stopped for destinations {}", destinationIds());
        }
    }

    @Override
    public boolean isRunning() {
        return scheduledTask != null;
    }

    @Override
    public boolean isAutoStartup() {
        return receiverProperties.getPolling().isEnabled();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException e) {
            // Should not normally trigger - poll() already isolates failures
            // per destination - but a scheduled task must never die either way.
            log.warn("FIT-Connect poll cycle failed unexpectedly, will retry next cycle", e);
        }
    }

    /** Runs exactly one poll cycle synchronously; package-private so tests can trigger it deterministically. */
    void poll() {
        int limit = receiverProperties.getPolling().getLimit();
        log.debug("Starting poll cycle across {} destination(s)", destinations.size());
        for (ReceivingDestination destination : destinations) {
            pollDestination(destination, limit);
        }
        log.debug("Poll cycle finished");
    }

    private void pollDestination(ReceivingDestination destination, int limit) {
        UUID destinationId = destination.destinationId();
        long startNanos = System.nanoTime();
        try {
            log.debug("Polling destination {} (limit={})", destinationId, limit);
            List<SubmissionForPickup> available = destination.client()
                    .getAvailableSubmissionsForDestination(destinationId, 0, limit);
            lastSuccessfulPollByDestination.put(destinationId, Instant.now());
            log.debug("Destination {} has {} submission(s) available", destinationId, available.size());
            for (SubmissionForPickup pickup : available) {
                submissionProcessor.process(destinationId, destination.client(), pickup.getSubmissionId());
            }
            metrics.pollCompleted(destinationId, elapsedSince(startNanos), available.size());
        } catch (RuntimeException e) {
            metrics.pollFailed(destinationId, elapsedSince(startNanos));
            // A transient failure on this destination must not stop the
            // others in the same cycle from being polled.
            log.warn("FIT-Connect poll of destination {} failed, will retry next cycle", destinationId, e);
        }
    }

    /**
     * When each destination was last polled successfully (its available-submissions
     * list retrieved without error), or absent if never. Used by {@code
     * FitConnectReceiverHealthIndicator}; returns an immutable snapshot.
     */
    public Map<UUID, Instant> lastSuccessfulPollByDestination() {
        return Map.copyOf(lastSuccessfulPollByDestination);
    }

    /** When {@link #start()} last scheduled the poller, or absent if it never has (e.g. {@code polling.enabled=false}). */
    public Instant startedAt() {
        return startedAt;
    }

    /** Delay between poll cycles, from {@code fitconnect.receiver.polling.interval}. */
    public Duration pollInterval() {
        return receiverProperties.getPolling().getInterval();
    }

    /** Delay before the first poll after {@link #start()}, from {@code fitconnect.receiver.polling.initial-delay}. */
    public Duration initialDelay() {
        return receiverProperties.getPolling().getInitialDelay();
    }

    /** The configured destination ids, in poll order. */
    public List<UUID> destinationIds() {
        return destinations.stream().map(ReceivingDestination::destinationId).toList();
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "fitconnect-poller");
        thread.setDaemon(true);
        return thread;
    }
}
