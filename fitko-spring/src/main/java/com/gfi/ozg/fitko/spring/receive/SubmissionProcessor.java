package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;
import java.util.UUID;

/**
 * Downloads one submission and publishes an {@link AntragReceivedEvent} for
 * it - the one piece of logic {@link AntragPollingService} (finds out about
 * a submission via polling) and the callback webhook endpoint (finds out via
 * an inbound HTTP notification) both need identically, once a submission id
 * and the {@link SubscriberClient} that owns its destination are known.
 */
public class SubmissionProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionProcessor.class);

    private final ApplicationEventPublisher eventPublisher;
    private final FitConnectProperties.Receiver receiverProperties;

    public SubmissionProcessor(ApplicationEventPublisher eventPublisher, FitConnectProperties.Receiver receiverProperties) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.receiverProperties = Objects.requireNonNull(receiverProperties, "receiverProperties must not be null");
    }

    /**
     * Fetches {@code submissionId} through {@code client}, publishes it, and
     * applies {@code fitconnect.receiver.default-outcome} if no listener
     * already resolved it. Never throws - a failure is logged and the
     * submission simply stays on the delivery service for a future attempt.
     */
    public void process(SubscriberClient client, UUID submissionId) {
        try {
            ReceivedSubmission submission = client.requestSubmission(submissionId);
            ReceivedAntrag antrag = new ReceivedAntrag(submission);
            eventPublisher.publishEvent(new AntragReceivedEvent(this, antrag));
            antrag.applyIfUnresolved(receiverProperties.getDefaultOutcome());
        } catch (RuntimeException e) {
            LOGGER.error("Failed to process submission {}, it stays on the delivery service", submissionId, e);
        }
    }
}
