package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.event.EventState;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.client.SenderClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-7b - the mirror of {@link SchemaValidationRoundTripIT}: with
 * {@code skip-submission-data-validation=true} + {@code disable-auto-reject=true}
 * (the suite default, made explicit here) a schema-invalid submission is
 * <em>not</em> auto-rejected - it is delivered as an event and the listener
 * decides, here rejecting it itself with a {@link DataSchemaViolation}.
 *
 * <p>Needs the real {@code FITCONNECT_IT_SERVICE_ID} + {@code FITCONNECT_IT_DATA_SCHEMA};
 * self-skips otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "fitconnect.receiver.skip-submission-data-validation=true",
        "fitconnect.receiver.disable-auto-reject=true"
})
@Import(RecordingListenerConfig.class)
class SchemaValidationOptOutRoundTripIT extends AbstractRoundTripIT {

    @Autowired
    RecordingListener listener;

    @Autowired
    SenderClient senderClient;

    @BeforeAll
    static void requireRealService() {
        ITCredentials.gate(ITCredentials.REAL_SERVICE_VARS);
    }

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void aSchemaInvalidSubmissionIsDeliveredAndTheListenerRejectsIt() {
        String marker = Payloads.newMarker(getClass());
        listener.rejectMarked(marker, new DataSchemaViolation());

        SentSubmission sent = send(SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (schema opt-out)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId())
                .build());

        // it IS delivered (transport works), unlike the auto-reject case
        RecordingListener.Received received = awaitReceived(listener, sent.getSubmissionId());
        assertThat(received.data()).contains(marker);

        // the listener's own reject then removes it, with the reason visible to the sender
        Awaitility.await("submission " + sent.getSubmissionId() + " rejected by the listener")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> senderClient.getSubmissionStatus(sent).getState() == EventState.REJECTED);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }
}
