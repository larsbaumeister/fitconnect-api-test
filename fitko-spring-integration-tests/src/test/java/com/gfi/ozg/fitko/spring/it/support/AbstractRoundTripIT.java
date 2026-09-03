package com.gfi.ozg.fitko.spring.it.support;

import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestinations;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.client.SenderClient;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Common wiring for every round-trip integration test:
 *
 * <ul>
 *   <li>skips the whole class unless the FIT-Connect credentials are set
 *       ({@link ITCredentials#requireBaseCredentials()});</li>
 *   <li>injects {@code fitconnect.*} credentials + {@code destinations[0]}
 *       from the environment ({@link ITProperties#registerBase});</li>
 *   <li>tears the context (and its background poller) down after the class
 *       ({@code @DirtiesContext}) so pollers from finished classes don't keep
 *       hitting FIT-Connect while later classes run;</li>
 *   <li>sweeps leftover suite submissions off the destination after each test
 *       ({@link OrphanSweep});</li>
 *   <li>provides {@link #awaitReceived} / {@link #assertNotRedelivered} on top
 *       of the real background poller.</li>
 * </ul>
 *
 * <p>Concrete tests add their own {@code @SpringBootTest} (with any per-test
 * {@code properties}) and a {@link RecordingListener} bean.
 */
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractRoundTripIT {

    protected static final Logger log = LoggerFactory.getLogger(AbstractRoundTripIT.class);

    /** How long a sent submission has to surface through the poller. FIT-Connect TEST is asynchronous and unhurried. */
    protected static final Duration RECEIVE_TIMEOUT = Duration.ofMinutes(3);

    /** How often to re-check while waiting for a round trip. */
    protected static final Duration RECEIVE_POLL = Duration.ofSeconds(5);

    /** Window over which "the submission was not redelivered" is asserted (several poll cycles). */
    protected static final Duration REDELIVERY_WINDOW = Duration.ofSeconds(20);

    @Autowired
    protected SubmissionSender submissionSender;

    @Autowired
    protected ObjectProvider<ReceivingDestinations> receivingDestinations;

    @Autowired
    protected ObjectProvider<SenderClient> senderClientProvider;

    /** Undecryptable junk on the shared destination is cleared once for the whole JVM run. */
    private static volatile boolean undecryptableCleared = false;

    @BeforeAll
    static void requireCredentials() {
        ITCredentials.requireBaseCredentials();
    }

    @DynamicPropertySource
    static void baseFitConnectProperties(DynamicPropertyRegistry registry) {
        ITProperties.registerBase(registry);
    }

    @BeforeEach
    void clearUndecryptableJunkOnce() {
        if (undecryptableCleared) {
            return;
        }
        synchronized (AbstractRoundTripIT.class) {
            if (undecryptableCleared) {
                return;
            }
            try {
                OrphanSweep.clearUndecryptable(receivingDestinations.getIfAvailable());
            } catch (RuntimeException e) {
                log.warn("Undecryptable-junk cleanup failed (ignored)", e);
            }
            undecryptableCleared = true;
        }
    }

    @AfterEach
    void sweepLeftoverSubmissions() {
        try {
            OrphanSweep.acceptSuiteLeftovers(receivingDestinations.getIfAvailable());
        } catch (RuntimeException e) {
            log.warn("Orphan sweep failed (ignored)", e);
        }
    }

    // --- round-trip helpers -------------------------------------------------

    protected SentSubmission send(SubmissionToSend submission) {
        SentSubmission sent = submissionSender.send(submission);
        log.info("Sent submission {} (case {}) to destination {}",
                sent.getSubmissionId(), sent.getCaseId(), sent.getDestinationId());
        return sent;
    }

    /** Blocks until the poller has delivered {@code sent} to {@code listener}, then returns the snapshot. */
    protected RecordingListener.Received awaitReceived(RecordingListener listener, SentSubmission sent) {
        try {
            Awaitility.await("round trip of submission " + sent.getSubmissionId())
                    .atMost(RECEIVE_TIMEOUT)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(RECEIVE_POLL)
                    .until(() -> listener.sawId(sent.getSubmissionId()));
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Submission " + sent.getSubmissionId() + " was sent but never delivered to a "
                    + "listener within " + RECEIVE_TIMEOUT + ". Server-side status: " + describeStatus(sent), e);
        }
        return listener.require(sent.getSubmissionId());
    }

    /** As above, when only the id is known. */
    protected RecordingListener.Received awaitReceived(RecordingListener listener, UUID submissionId) {
        Awaitility.await("round trip of submission " + submissionId)
                .atMost(RECEIVE_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(RECEIVE_POLL)
                .until(() -> listener.sawId(submissionId));
        return listener.require(submissionId);
    }

    /** Best-effort server-side status of a sent submission, for failure messages. */
    protected String describeStatus(SentSubmission sent) {
        SenderClient client = senderClientProvider.getIfAvailable();
        if (client == null) {
            return "<no SenderClient>";
        }
        try {
            var status = client.getSubmissionStatus(sent);
            return status.getState() + (status.getProblems() == null || status.getProblems().isEmpty()
                    ? "" : " " + status.getProblems().stream().map(p -> p.getType()).toList());
        } catch (Exception e) {
            return "<status unavailable: " + e.getMessage() + ">";
        }
    }

    /**
     * Asserts {@code submissionId} is not delivered again for {@link #REDELIVERY_WINDOW}
     * (i.e. it really was removed server-side by {@code accept()}/{@code reject()}).
     * Call after the resolving round trip.
     */
    protected void assertNotRedelivered(RecordingListener listener, UUID submissionId) {
        int seen = listener.timesSeen(submissionId);
        sleep(REDELIVERY_WINDOW);
        assertThat(listener.timesSeen(submissionId))
                .as("submission %s must not be redelivered once resolved", submissionId)
                .isEqualTo(seen);
    }

    protected static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }
}
