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

/**
 * Repeatedly polls one destination for available submissions and publishes
 * an {@link AntragReceivedEvent} for each. Runs on its own single-thread
 * scheduler (not the application's {@code TaskScheduler}/{@code @Scheduled}
 * infrastructure), started and stopped along with the Spring lifecycle.
 */
public class AntragPollingService implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(AntragPollingService.class);

    private final SubscriberClient subscriberClient;
    private final ApplicationEventPublisher eventPublisher;
    private final UUID destinationId;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ScheduledExecutorService scheduler;

    private volatile ScheduledFuture<?> scheduledTask;

    public AntragPollingService(SubscriberClient subscriberClient, ApplicationEventPublisher eventPublisher,
                                 UUID destinationId, FitConnectProperties.Receiver receiverProperties) {
        this.subscriberClient = Objects.requireNonNull(subscriberClient, "subscriberClient must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.destinationId = Objects.requireNonNull(destinationId,
                "fitconnect.destination-id must be set to poll for submissions");
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
        LOGGER.info("FIT-Connect polling started for destination {} (every {})", destinationId, interval);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> task = scheduledTask;
        scheduledTask = null;
        if (task != null) {
            task.cancel(false);
            LOGGER.info("FIT-Connect polling stopped for destination {}", destinationId);
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
            // A transient failure (network blip, expired token, ...) must
            // never kill the scheduled task - there is always a next poll.
            LOGGER.warn("FIT-Connect poll of destination {} failed, will retry next cycle", destinationId, e);
        }
    }

    /** Runs exactly one poll cycle synchronously; package-private so tests can trigger it deterministically. */
    void poll() {
        int limit = receiverProperties.getPolling().getLimit();
        List<SubmissionForPickup> available =
                subscriberClient.getAvailableSubmissionsForDestination(destinationId, 0, limit);
        for (SubmissionForPickup pickup : available) {
            processOne(pickup.getSubmissionId());
        }
    }

    private void processOne(UUID submissionId) {
        try {
            ReceivedSubmission submission = subscriberClient.requestSubmission(submissionId);
            ReceivedAntrag antrag = new ReceivedAntrag(submission);
            eventPublisher.publishEvent(new AntragReceivedEvent(this, antrag));
            antrag.applyIfUnresolved(receiverProperties.getDefaultOutcome());
        } catch (RuntimeException e) {
            LOGGER.error("Failed to process submission {}, it stays on the delivery service", submissionId, e);
        }
    }

    private static Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "fitconnect-poller");
        thread.setDaemon(true);
        return thread;
    }
}
