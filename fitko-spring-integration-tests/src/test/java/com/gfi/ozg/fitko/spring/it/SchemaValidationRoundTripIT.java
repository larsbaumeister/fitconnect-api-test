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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-7 - real schema validation and server-side auto-reject, the one class
 * that turns {@code skip-submission-data-validation} / {@code disable-auto-reject}
 * back on. Needs the real {@code FITCONNECT_IT_SERVICE_ID} +
 * {@code FITCONNECT_IT_DATA_SCHEMA} to be set; self-skips otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "fitconnect.receiver.skip-submission-data-validation=false",
        "fitconnect.receiver.disable-auto-reject=false"
})
@Import(RecordingListenerConfig.class)
class SchemaValidationRoundTripIT extends AbstractRoundTripIT {

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
    void aSchemaInvalidSubmissionIsAutoRejectedAndNeverDelivered() {
        String marker = Payloads.newMarker(getClass());

        // Payloads.xml is well-formed but does not conform to the real dataSchema.
        SentSubmission sent = send(realServiceSubmission(Payloads.xml(marker)).build());

        Awaitility.await("submission " + sent.getSubmissionId() + " auto-rejected server-side")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> senderClient.getSubmissionStatus(sent).getState() == EventState.REJECTED);

        // auto-reject happens inside the SDK's requestSubmission, before any event is published
        sleep(Duration.ofSeconds(15));
        assertThat(listener.sawId(sent.getSubmissionId()))
                .as("no SubmissionReceivedEvent for an auto-rejected submission")
                .isFalse();
    }

    @Test
    void aValidSubmissionAgainstTheRealSchemaRoundTrips() {
        String validPayloadLocation = ITCredentials.env("FITCONNECT_IT_VALID_PAYLOAD", null);
        Assumptions.assumeTrue(validPayloadLocation != null,
                "set FITCONNECT_IT_VALID_PAYLOAD to a resource holding a schema-valid instance document");

        String payload = read(validPayloadLocation);
        String marker = Payloads.newMarker(getClass());
        listener.resolution(RecordingListener.Resolution.ACCEPT).resolveWhen(r -> r.submissionId() != null);

        SentSubmission sent = send(realServiceSubmission(payload).build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.data()).isEqualTo(payload);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    private SubmissionToSend.Builder realServiceSubmission(String payload) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (schema)", DataFormat.XML,
                        payload, URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId());
    }

    private static String read(String location) {
        try {
            return new String(new DefaultResourceLoader().getResource(location).getContentAsByteArray(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read FITCONNECT_IT_VALID_PAYLOAD from " + location, e);
        }
    }
}
