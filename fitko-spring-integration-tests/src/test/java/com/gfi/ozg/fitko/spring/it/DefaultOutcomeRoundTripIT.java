package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.event.EventState;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.client.SenderClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Duration;

/**
 * IT-4d - {@code fitconnect.receiver.default-outcome=ACCEPT}: a submission
 * that no listener resolves is accepted by the starter after every listener
 * has run, and is then gone server-side. (The REJECT default is the exact
 * mirror and is not exercised separately to avoid rejecting submissions on
 * the shared destination for a low-value check.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "fitconnect.receiver.default-outcome=ACCEPT")
@Import(RecordingListenerConfig.class)
class DefaultOutcomeRoundTripIT extends AbstractRoundTripIT {

    @Autowired
    RecordingListener listener;

    @Autowired
    SenderClient senderClient;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void anUnresolvedSubmissionIsAutoAcceptedByTheConfiguredDefaultOutcome() {
        String marker = Payloads.newMarker(getClass());
        listener.resolution(RecordingListener.Resolution.LEAVE); // no listener resolves it -> default-outcome decides

        SentSubmission sent = send(SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (default-outcome)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId())
                .build());
        awaitReceived(listener, sent.getSubmissionId());

        Awaitility.await("submission " + sent.getSubmissionId() + " auto-accepted")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> senderClient.getSubmissionStatus(sent).getState() == EventState.ACCEPTED);

        assertNotRedelivered(listener, sent.getSubmissionId());
    }
}
