package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * publishes an {@link AntragReceivedEvent} for each. Runs on its own
 * single-thread scheduler (not the application's {@code
 * TaskScheduler}/{@code @Scheduled} infrastructure), started and stopped
 * along with the Spring lifecycle.
 *
 * <p>Each {@link PolledDestination} carries its own {@link SubscriberClient} -
 * a FIT-Connect Zustellpunkt is registered with its own signing/decryption
 * keys, and the SDK bakes exactly one key set into each {@code
 * SubscriberClient} instance, so two destinations with different keys need
 * two separate clients even though one {@code AntragPollingService} still
 * polls both. Destinations are polled one after another within a single
 * cycle; a failure polling one (network blip, expired token, ...) is logged
 * and does not stop the others in the same cycle from being polled.
 */
public class AntragPollingService implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(AntragPollingService.class);

    private final List<PolledDestination> destinations;
    private final ApplicationEventPublisher eventPublisher;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ScheduledExecutorService scheduler;

    private volatile ScheduledFuture<?> scheduledTask;

    public AntragPollingService(List<PolledDestination> destinations, ApplicationEventPublisher eventPublisher,
                                 FitConnectProperties.Receiver receiverProperties) {
        this.destinations = List.copyOf(Objects.requireNonNull(destinations,
                "fitconnect.receiver.destinations must be set to poll for submissions"));
        if (this.destinations.isEmpty()) {
            throw new IllegalArgumentException(
                    "fitconnect.receiver.destinations must contain at least one destination");
        }
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
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
        for (PolledDestination destination : destinations) {
            pollDestination(destination, limit);
        }
    }

    private void pollDestination(PolledDestination destination, int limit) {
        try {
            List<SubmissionForPickup> available = destination.client()
                    .getAvailableSubmissionsForDestination(destination.destinationId(), 0, limit);
            for (SubmissionForPickup pickup : available) {
                processOne(destination.client(), pickup.getSubmissionId());
            }
        } catch (RuntimeException e) {
            // A transient failure on this destination must not stop the
            // others in the same cycle from being polled.
            LOGGER.warn("FIT-Connect poll of destination {} failed, will retry next cycle",
                    destination.destinationId(), e);
        }
    }

    private void processOne(SubscriberClient client, UUID submissionId) {
        try {
            ReceivedSubmission submission = client.requestSubmission(submissionId);
            ReceivedAntrag antrag = new ReceivedAntrag(submission);
            eventPublisher.publishEvent(new AntragReceivedEvent(this, antrag));
            antrag.applyIfUnresolved(receiverProperties.getDefaultOutcome());
        } catch (RuntimeException e) {
            LOGGER.error("Failed to process submission {}, it stays on the delivery service", submissionId, e);
        }
    }

    private List<UUID> destinationIds() {
        return destinations.stream().map(PolledDestination::destinationId).collect(Collectors.toList());
    }

    private static Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "fitconnect-poller");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * One polled destination and the {@link SubscriberClient} configured
     * with its keys - see the class javadoc for why every destination needs
     * its own client instead of sharing one.
     */
    public record PolledDestination(UUID destinationId, SubscriberClient client) {

        public PolledDestination {
            Objects.requireNonNull(destinationId, "destinationId must not be null");
            Objects.requireNonNull(client, "client must not be null");
        }
    }
}
