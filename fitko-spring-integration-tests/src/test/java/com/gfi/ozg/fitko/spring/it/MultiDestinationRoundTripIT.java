package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.ITProperties;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.it.support.ThrowawayJwks;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-6 - one poller, several destinations. Each destination is polled through
 * its own {@code SubscriberClient} with its own keys: a submission for
 * destination 1 is only ever delivered as a destination-1 event, and a
 * destination in the list whose keys are wrong does not stop the healthy ones
 * from delivering in the same cycle.
 *
 * <p>Needs the {@code FITCONNECT_IT_DESTINATION2_*} variables; self-skips
 * otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RecordingListenerConfig.class)
class MultiDestinationRoundTripIT extends AbstractRoundTripIT {

    @Autowired
    RecordingListener listener;

    @BeforeAll
    static void requireSecondDestination() {
        ITCredentials.gate(ITCredentials.SECOND_DESTINATION_VARS);
    }

    @DynamicPropertySource
    static void extraDestinations(DynamicPropertyRegistry registry) {
        ITProperties.registerSecondDestination(registry);
        // destinations[2]: the real destination-1 id but with throwaway keys.
        // The client builds fine (the destination exists) and lists submissions,
        // but every requestSubmission() fails to decrypt - the failure-isolation
        // case, without needing an id that might fail client construction.
        registry.add("fitconnect.receiver.destinations[2].id", () -> ITCredentials.destinationId().toString());
        registry.add("fitconnect.receiver.destinations[2].signing-key", ThrowawayJwks::signingKeyResource);
        registry.add("fitconnect.receiver.destinations[2].decryption-keys[0]", ThrowawayJwks::decryptionKeyResource);
    }

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void eachDestinationDeliversOnlyItsOwnSubmissions() {
        String marker1 = Payloads.newMarker(getClass());
        String marker2 = Payloads.newMarker(getClass());
        listener.resolution(RecordingListener.Resolution.ACCEPT)
                .resolveWhen(r -> r.data() != null && (r.data().contains(marker1) || r.data().contains(marker2)));

        SentSubmission sent1 = send(submissionTo(ITCredentials.destinationId(), marker1));
        SentSubmission sent2 = send(submissionTo(ITCredentials.secondDestinationId(), marker2));

        RecordingListener.Received received1 = awaitReceived(listener, sent1);
        RecordingListener.Received received2 = awaitReceived(listener, sent2);

        assertThat(received1.destinationId()).isEqualTo(ITCredentials.destinationId());
        assertThat(received1.data()).contains(marker1);
        assertThat(received2.destinationId()).isEqualTo(ITCredentials.secondDestinationId());
        assertThat(received2.data()).contains(marker2);

        assertNotRedelivered(listener, sent1.getSubmissionId());
        assertNotRedelivered(listener, sent2.getSubmissionId());
    }

    @Test
    void aBrokenDestinationInTheListDoesNotStopTheHealthyOnes() {
        // destinations[2] shares destination-1's id but has the wrong keys, so
        // every requestSubmission() on it fails; the real destination must
        // still complete a round trip in the same cycles.
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(submissionTo(ITCredentials.destinationId(), marker));
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.data()).contains(marker);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    private static SubmissionToSend submissionTo(UUID destinationId, String marker) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (multi-destination)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(destinationId)
                .build();
    }
}
