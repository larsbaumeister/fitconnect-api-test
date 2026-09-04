package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestinations;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Repeatedly polls one or more destinations for available submissions and
 * hands each page to a {@link SafeguardedSubmissionRunner}. Runs on its own
 * single-thread scheduler (not the application's {@code TaskScheduler}/{@code
 * @Scheduled} infrastructure), started and stopped along with the Spring
 * lifecycle.
 *
 * <p>Each {@link ReceivingDestination} carries its own pool of {@link
 * dev.fitko.fitconnect.client.SubscriberClient}s - a FIT-Connect Zustellpunkt
 * is registered with its own signing/decryption keys, and the SDK bakes
 * exactly one key set into each client instance, so two destinations with
 * different keys need separate clients even though one {@code
 * SubmissionPollingService} still polls both. Destinations are polled one
 * after another within a cycle; a failure polling one (network blip, expired
 * token, ...) is logged and does not stop the others in the same cycle from
 * being polled. Within a single destination's page, submissions are
 * downloaded, decrypted, published and resolved <em>in parallel</em>, up to
 * {@code fitconnect.receiver.polling.concurrency} at a time - see {@link
 * SafeguardedSubmissionRunner}, which also owns the per-submission {@code
 * submission-timeout} and {@code retry-cooldown} safeguards.
 *
 * <p>{@code poll()} does not return until every submission of every
 * destination in the cycle has finished, so poll cycles never overlap and any
 * ShedLock lock a {@link PollCycleGate} holds still spans the whole cycle.
 *
 * <p>Polling and the callback webhook endpoint (see {@code
 * FitConnectCallbackController}) are independent: a destination with a
 * callback secret configured is still polled here too, so a missed or failed
 * callback delivery is simply picked up on the next poll cycle. When the
 * optional ShedLock integration is wired (see {@code
 * FitConnectPollLockAutoConfiguration}), only <em>polling</em> is coordinated
 * across replicas via {@link PollCycleGate}; the callback endpoint is never
 * gated by that lock.
 *
 * <p>Per-cycle timings and per-destination counts are reported to {@link
 * ReceivePipelineMetrics} (a no-op unless Micrometer is on the classpath).
 * {@code FitConnectReceiverHealthIndicator} only reports whether this
 * instance's poller thread is running, nothing more.
 */
@Slf4j
public class SubmissionPollingService implements SmartLifecycle {

    private final List<ReceivingDestination> destinations;
    private final SafeguardedSubmissionRunner runner;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ReceivePipelineMetrics metrics;
    private final ScheduledExecutorService scheduler;

    // Wraps each scheduled poll cycle. PollCycleGate.DIRECT (run it straight
    // away) unless the optional ShedLock integration is wired, in which case
    // only one replica runs any given cycle. See FitConnectPollLockAutoConfiguration.
    private final PollCycleGate pollCycleGate;

    private volatile ScheduledFuture<?> scheduledTask;

    public SubmissionPollingService(ReceivingDestinations destinations, SafeguardedSubmissionRunner runner,
                                    FitConnectProperties.Receiver receiverProperties, ReceivePipelineMetrics metrics,
                                    PollCycleGate pollCycleGate) {
        this.destinations = Objects.requireNonNull(destinations,
                "fitconnect.receiver.destinations must be set to poll for submissions").all();
        if (this.destinations.isEmpty()) {
            throw new IllegalArgumentException(
                    "fitconnect.receiver.destinations must contain at least one destination");
        }
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.receiverProperties = Objects.requireNonNull(receiverProperties, "receiverProperties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.pollCycleGate = Objects.requireNonNull(pollCycleGate, "pollCycleGate must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(SubmissionPollingService::newDaemonThread);
    }

    @Override
    public void start() {
        if (scheduledTask != null) {
            return;
        }
        Duration initialDelay = receiverProperties.getPolling().getInitialDelay();
        Duration interval = receiverProperties.getPolling().getInterval();
        scheduledTask = scheduler.scheduleWithFixedDelay(
                this::pollSafely, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("FIT-Connect polling started for destinations {} (every {}, concurrency {})",
                destinationIds(), interval, runner.concurrency());
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

    // Package-private so a test can exercise the PollCycleGate wrapper; the
    // integration tests still call poll() directly, which bypasses the gate.
    void pollSafely() {
        try {
            pollCycleGate.runPollCycle(this::poll);
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
            List<SubmissionForPickup> available = destination.withClient(
                    client -> client.getAvailableSubmissionsForDestination(destinationId, 0, limit));
            log.debug("Destination {} has {} submission(s) available", destinationId, available.size());
            List<UUID> submissionIds = available.stream().map(SubmissionForPickup::getSubmissionId).toList();
            runner.processPage(destinationId, destination, submissionIds);
            metrics.pollCompleted(destinationId, elapsedSince(startNanos), available.size());
        } catch (RuntimeException e) {
            metrics.pollFailed(destinationId, elapsedSince(startNanos));
            // A transient failure on this destination must not stop the
            // others in the same cycle from being polled.
            log.warn("FIT-Connect poll of destination {} failed, will retry next cycle", destinationId, e);
        }
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
