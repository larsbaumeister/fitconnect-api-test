package dev.fitko.fitconnect.samples.receiver;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;

import java.util.List;
import java.util.UUID;

/** Thin wrapper around the two {@link SubscriberClient} calls this sample needs. */
final class SubmissionPickupService {

    private final SubscriberClient subscriberClient;

    SubmissionPickupService(SubscriberClient subscriberClient) {
        this.subscriberClient = subscriberClient;
    }

    List<SubmissionForPickup> listAvailable(UUID destinationId, int offset, int limit) {
        return subscriberClient.getAvailableSubmissionsForDestination(destinationId, offset, limit);
    }

    ReceivedSubmission fetch(UUID submissionId) {
        return subscriberClient.requestSubmission(submissionId);
    }
}
