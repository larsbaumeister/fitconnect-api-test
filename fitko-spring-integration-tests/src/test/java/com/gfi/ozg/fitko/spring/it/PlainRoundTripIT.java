package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-1 - the backbone round trip: what goes in comes back out, unchanged,
 * through the real background poller, and {@code accept()} removes it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RecordingListenerConfig.class)
class PlainRoundTripIT extends AbstractRoundTripIT {

    @Autowired
    RecordingListener listener;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void xmlPayloadRoundTripsByteForByte() {
        String marker = Payloads.newMarker(getClass());
        String payload = Payloads.xml(marker);
        listener.acceptMarked(marker);

        SentSubmission sent = send(xmlSubmission(payload).build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.data()).isEqualTo(payload);
        assertThat(received.dataMimeType()).containsIgnoringCase("xml");
        assertThat(received.serviceId()).isEqualTo(ITCredentials.serviceId());
        assertThat(received.destinationId()).isEqualTo(ITCredentials.destinationId());
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void jsonPayloadRoundTrips() {
        Assumptions.assumeTrue(ITCredentials.hasJsonService(),
                "set FITCONNECT_IT_JSON_SERVICE_ID + FITCONNECT_IT_JSON_DATA_SCHEMA to a JSON service "
                        + "the fixture destination accepts");
        String marker = Payloads.newMarker(getClass());
        String payload = Payloads.json(marker);
        listener.acceptMarked(marker);

        SentSubmission sent = send(SubmissionToSend.builder(
                        ITCredentials.jsonServiceId(), "fitko-spring IT (json)", DataFormat.JSON,
                        payload, URI.create(ITCredentials.jsonDataSchema()))
                .destinationId(ITCredentials.destinationId())
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.data()).isEqualTo(payload);
        assertThat(received.dataMimeType()).containsIgnoringCase("json");
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void theCaseIdReportedBySendIsTheOneTheReceiverSees() {
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(xmlSubmission(Payloads.xml(marker)).build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.caseId()).isEqualTo(sent.getCaseId());
        assertThat(received.submissionId()).isEqualTo(sent.getSubmissionId());
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void anEmailReplyChannelSurvives() {
        Assumptions.assumeTrue(ITCredentials.emailReplyChannelSupported(),
                "set FITCONNECT_IT_EMAIL_REPLY_CHANNEL=true if the fixture destination accepts an e-mail reply channel");
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(xmlSubmission(Payloads.xml(marker))
                .replyChannelEmail("applicant@example.org")
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.metadata()).isNotNull();
        assertThat(received.metadata().getReplyChannel()).isNotNull();
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void serviceRegionRoundTripsWhenSet() {
        String region = ITCredentials.serviceRegion();
        Assumptions.assumeTrue(region != null,
                "set FITCONNECT_IT_SERVICE_REGION to a region code the fixture destination's service is registered for");
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(xmlSubmission(Payloads.xml(marker))
                .serviceRegion(region)
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.region()).isEqualTo(region);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    private SubmissionToSend.Builder xmlSubmission(String payload) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT", DataFormat.XML,
                        payload, URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId());
    }
}
