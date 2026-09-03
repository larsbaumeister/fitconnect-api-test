package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.event.EventState;
import dev.fitko.fitconnect.api.domain.model.event.Status;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-4 - what a listener does with a submission is what FIT-Connect does with
 * it: {@code accept()} and {@code reject(...)} both remove it server-side (the
 * sender sees ACCEPTED / REJECTED and it is never redelivered), while leaving
 * it unresolved has it redelivered every poll cycle until something resolves
 * it (the at-least-once contract).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RecordingListenerConfig.class)
class OutcomeRoundTripIT extends AbstractRoundTripIT {

    private static final Duration STATUS_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    RecordingListener listener;

    @Autowired
    SenderClient senderClient;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void acceptRemovesTheSubmissionServerSide() {
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker).build());
        awaitReceived(listener, sent.getSubmissionId());

        awaitState(sent, EventState.ACCEPTED);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void rejectRemovesTheSubmissionAndCarriesTheReasonToTheSender() {
        String marker = Payloads.newMarker(getClass());
        listener.rejectMarked(marker, new DataSchemaViolation());

        SentSubmission sent = send(submission(marker).build());
        awaitReceived(listener, sent.getSubmissionId());

        Status status = awaitState(sent, EventState.REJECTED);
        assertThat(status.getProblems())
                .as("rejection problems visible to the sender")
                .anySatisfy(p -> assertThat(p.getType()).contains("schema-violation"));
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void anUnresolvedSubmissionIsRedeliveredEveryCycleUntilResolved() {
        String marker = Payloads.newMarker(getClass());
        listener.resolution(RecordingListener.Resolution.LEAVE); // the default, made explicit

        SentSubmission sent = send(submission(marker).build());
        awaitReceived(listener, sent.getSubmissionId());

        // still on the delivery service -> re-downloaded and re-published on subsequent cycles
        Awaitility.await("redelivery of " + sent.getSubmissionId())
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> listener.timesSeen(sent.getSubmissionId()) >= 2);

        // now resolve it and confirm redelivery stops
        listener.acceptMarked(marker);
        awaitState(sent, EventState.ACCEPTED);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    private Status awaitState(SentSubmission sent, EventState expected) {
        Awaitility.await("submission " + sent.getSubmissionId() + " reaches " + expected)
                .atMost(STATUS_TIMEOUT)
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> senderClient.getSubmissionStatus(sent).getState() == expected);
        return senderClient.getSubmissionStatus(sent);
    }

    private SubmissionToSend.Builder submission(String marker) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (outcome)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId());
    }
}
