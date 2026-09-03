package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;
import java.util.UUID;

/**
 * Downloads one submission and publishes an {@link SubmissionReceivedEvent} for
 * it - the one piece of logic {@link SubmissionPollingService} (finds out about
 * a submission via polling) and the callback webhook endpoint (finds out via
 * an inbound HTTP notification) both need identically, once a submission id
 * and the {@link SubscriberClient} that owns its destination are known.
 */
@Slf4j
public class SubmissionProcessor {

    private final ApplicationEventPublisher eventPublisher;
    private final FitConnectProperties.Receiver receiverProperties;
    private final ReceivePipelineMetrics metrics;

    public SubmissionProcessor(ApplicationEventPublisher eventPublisher, FitConnectProperties.Receiver receiverProperties,
                               ReceivePipelineMetrics metrics) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.receiverProperties = Objects.requireNonNull(receiverProperties, "receiverProperties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * Fetches {@code submissionId} through {@code client}, publishes it, and
     * applies {@code fitconnect.receiver.default-outcome} if no listener
     * already resolved it. Never throws - a failure is logged and the
     * submission simply stays on the delivery service for a future attempt.
     * {@code destinationId} is used only to tag metrics.
     *
     * @return {@code true} if the submission was downloaded and published
     * without error, {@code false} if it failed (callers such as {@code
     * SubmissionPollingService} use this to drive {@code polling.retry-cooldown}).
     */
    public boolean process(UUID destinationId, SubscriberClient client, UUID submissionId) {
        try {
            log.debug("Fetching submission {}", submissionId);
            ReceivedSubmission submission = client.requestSubmission(submissionId);
            IncomingSubmission incoming = new IncomingSubmission(submission);
            eventPublisher.publishEvent(new SubmissionReceivedEvent(this, incoming));
            if (incoming.isResolved()) {
                log.debug("Submission {} was accepted/rejected by a listener", submissionId);
            } else {
                DefaultOutcome outcome = receiverProperties.getDefaultOutcome();
                log.debug("Submission {} was not resolved by any listener, applying default outcome {}",
                        submissionId, outcome);
                incoming.applyIfUnresolved(outcome);
            }
            metrics.submissionProcessed(destinationId);
            return true;
        } catch (RuntimeException e) {
            metrics.submissionFailed(destinationId);
            log.error("Failed to process submission {}, it stays on the delivery service", submissionId, e);
            return false;
        }
    }
}
