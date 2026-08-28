package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 */
public class AntragPollingService implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(AntragPollingService.class);

    private final List<ReceivingDestination> destinations;
    private final SubmissionProcessor submissionProcessor;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ScheduledExecutorService scheduler;

    private volatile ScheduledFuture<?> scheduledTask;

    public AntragPollingService(List<ReceivingDestination> destinations, SubmissionProcessor submissionProcessor,
                                 FitConnectProperties.Receiver receiverProperties) {
        this.destinations = List.copyOf(Objects.requireNonNull(destinations,
                "fitconnect.receiver.destinations must be set to poll for submissions"));
        if (this.destinations.isEmpty()) {
            throw new IllegalArgumentException(
                    "fitconnect.receiver.destinations must contain at least one destination");
        }
        this.submissionProcessor = Objects.requireNonNull(submissionProcessor, "submissionProcessor must not be null");
        this.receiverProperties = Objects.requireNonNull(receiverProperties, "receiverProperties must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(AntragPollingService::newDaemonThread);
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
        LOGGER.info("FIT-Connect polling started for destinations {} (every {})", destinationIds(), interval);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> task = scheduledTask;
        scheduledTask = null;
        if (task != null) {
            task.cancel(false);
            LOGGER.info("FIT-Connect polling stopped for destinations {}", destinationIds());
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
            LOGGER.warn("FIT-Connect poll cycle failed unexpectedly, will retry next cycle", e);
        }
    }

    /** Runs exactly one poll cycle synchronously; package-private so tests can trigger it deterministically. */
    void poll() {
        int limit = receiverProperties.getPolling().getLimit();
        for (ReceivingDestination destination : destinations) {
            pollDestination(destination, limit);
        }
    }

    private void pollDestination(ReceivingDestination destination, int limit) {
        try {
            List<SubmissionForPickup> available = destination.client()
                    .getAvailableSubmissionsForDestination(destination.destinationId(), 0, limit);
            for (SubmissionForPickup pickup : available) {
                submissionProcessor.process(destination.client(), pickup.getSubmissionId());
            }
        } catch (RuntimeException e) {
            // A transient failure on this destination must not stop the
            // others in the same cycle from being polled.
            LOGGER.warn("FIT-Connect poll of destination {} failed, will retry next cycle",
                    destination.destinationId(), e);
        }
    }

    private List<UUID> destinationIds() {
        return destinations.stream().map(ReceivingDestination::destinationId).collect(Collectors.toList());
    }

    private static Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "fitconnect-poller");
        thread.setDaemon(true);
        return thread;
    }
}
