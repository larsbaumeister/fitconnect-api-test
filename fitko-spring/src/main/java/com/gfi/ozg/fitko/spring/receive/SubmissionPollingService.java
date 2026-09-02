package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * SubmissionPollingService} still polls both. Destinations are polled one after
 * another within a single cycle; a failure polling one (network blip,
 * expired token, ...) is logged and does not stop the others in the same
 * cycle from being polled.
 *
 * <p>Polling and the callback webhook endpoint (see {@code
 * FitConnectCallbackController}) are independent and not mutually exclusive:
 * a destination with {@code fitconnect.receiver.callback.enabled} and its
 * own {@code callback-secret} configured is still polled here too - a missed
 * or failed callback delivery is simply picked up on the next poll cycle
 * instead of being lost. When the optional ShedLock integration is wired
 * (see {@code FitConnectPollLockAutoConfiguration}), only <em>polling</em>
 * is coordinated across replicas via {@link PollCycleGate}; the callback
 * endpoint is never gated by that lock.
 *
 * <p>Per-cycle timings and counts are reported to {@link ReceivePipelineMetrics}
 * (a no-op unless Micrometer is on the classpath) - that is where "polling is
 * healthy but idle" vs "polling has been failing" is visible.
 * {@code FitConnectReceiverHealthIndicator} only reports whether this
 * instance's poller thread is running, nothing more.
 *
 * <p>Two independent, opt-in safeguards protect the single poller thread from
 * one bad submission: {@code polling.submission-timeout} (default 10s, always
 * on) bounds how long any one submission's download/decrypt/publish/listener
 * work may run before it's abandoned for this cycle, and {@code
 * polling.retry-cooldown} (unset by default, i.e. off) skips re-fetching a
 * submission that failed until the configured time has passed instead of
 * retrying it every single cycle. See each property's javadoc on {@link
 * com.gfi.ozg.fitko.spring.FitConnectProperties.Polling}. The retry-cooldown
 * failure timestamps are held in a Spring {@link org.springframework.cache.Cache}
 * (see {@link RetryCooldownStore}), so they can be backed by Redis and shared
 * across replicas, and are pruned once the cooldown elapses.
 */
@Slf4j
public class SubmissionPollingService implements SmartLifecycle {

    private final List<ReceivingDestination> destinations;
    private final SubmissionProcessor submissionProcessor;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ReceivePipelineMetrics metrics;
    private final ScheduledExecutorService scheduler;

    // Retry-cooldown bookkeeping for polling.retry-cooldown. RetryCooldownStore.NONE
    // (a no-op) whenever the property is unset, so the feature costs nothing then;
    // otherwise a Spring Cache-backed store that can be shared across replicas.
    private final RetryCooldownStore retryCooldownStore;

    // Each submission runs on its own short-lived worker thread so poll()
    // can bound it with a timeout (see processWithTimeout) without touching
    // the single-threaded polling model otherwise - the poller thread still
    // waits for one submission before starting the next.
    private final ExecutorService submissionExecutor;

    // Wraps each scheduled poll cycle. PollCycleGate.DIRECT (run it straight
    // away) unless the optional ShedLock integration is wired, in which case
    // only one replica runs any given cycle. See FitConnectPollLockAutoConfiguration.
    private final PollCycleGate pollCycleGate;

    private volatile ScheduledFuture<?> scheduledTask;

    public SubmissionPollingService(ReceivingDestinations destinations, SubmissionProcessor submissionProcessor,
                                 FitConnectProperties.Receiver receiverProperties, ReceivePipelineMetrics metrics,
                                 RetryCooldownStore retryCooldownStore, PollCycleGate pollCycleGate) {
        this.destinations = Objects.requireNonNull(destinations,
                "fitconnect.receiver.destinations must be set to poll for submissions").all();
        if (this.destinations.isEmpty()) {
            throw new IllegalArgumentException(
                    "fitconnect.receiver.destinations must contain at least one destination");
        }
        this.submissionProcessor = Objects.requireNonNull(submissionProcessor, "submissionProcessor must not be null");
        this.receiverProperties = Objects.requireNonNull(receiverProperties, "receiverProperties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.retryCooldownStore = Objects.requireNonNull(retryCooldownStore, "retryCooldownStore must not be null");
        this.pollCycleGate = Objects.requireNonNull(pollCycleGate, "pollCycleGate must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(SubmissionPollingService::newDaemonThread);
        this.submissionExecutor = Executors.newCachedThreadPool(SubmissionPollingService::newSubmissionWorkerThread);
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
        submissionExecutor.shutdownNow();
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
            List<SubmissionForPickup> available = destination.client()
                    .getAvailableSubmissionsForDestination(destinationId, 0, limit);
            log.debug("Destination {} has {} submission(s) available", destinationId, available.size());
            for (SubmissionForPickup pickup : available) {
                processWithSafeguards(destinationId, destination.client(), pickup.getSubmissionId());
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
     * Applies {@code polling.retry-cooldown} via {@link RetryCooldownStore}
     * (skip if this submission failed too recently), then runs it through
     * {@link #processWithTimeout}, then feeds the outcome back to the store.
     * With {@code polling.retry-cooldown} unset the store is {@link
     * RetryCooldownStore#NONE} and this is a straight call to {@code
     * submissionProcessor.process} - the default, unchanged behaviour.
     */
    private void processWithSafeguards(UUID destinationId, SubscriberClient client, UUID submissionId) {
        if (retryCooldownStore.isCoolingDown(submissionId)) {
            return;
        }
        boolean succeeded = processWithTimeout(destinationId, client, submissionId);
        retryCooldownStore.recordOutcome(submissionId, succeeded);
    }

    /**
     * Runs {@code submissionProcessor.process} on {@link #submissionExecutor}
     * and waits at most {@code polling.submission-timeout} for it. On timeout
     * the worker is interrupted (best-effort - see the property's javadoc)
     * and this returns {@code false} without waiting for it any further, so
     * one stuck submission never stalls the rest of the cycle.
     */
    private boolean processWithTimeout(UUID destinationId, SubscriberClient client, UUID submissionId) {
        Duration timeout = receiverProperties.getPolling().getSubmissionTimeout();
        Future<Boolean> future = submissionExecutor.submit(
                () -> submissionProcessor.process(destinationId, client, submissionId));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            metrics.submissionFailed(destinationId);
            log.error("Processing submission {} exceeded the {} submission-timeout, abandoning it for this cycle",
                    submissionId, timeout);
            return false;
        } catch (ExecutionException e) {
            // submissionProcessor.process() is documented to never throw, so
            // this is a defensive fallback, not an expected path.
            metrics.submissionFailed(destinationId);
            log.error("Unexpected exception processing submission {}", submissionId, e.getCause());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("Interrupted while waiting for submission {} to be processed", submissionId);
            return false;
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

    private static Thread newSubmissionWorkerThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "fitconnect-submission-worker");
        thread.setDaemon(true);
        return thread;
    }
}
