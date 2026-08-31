package com.example.gewerbeamt.receive;

import com.example.gewerbeamt.Leistung;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Handles incoming Gewerbeanmeldung submissions.
 *
 * <p>The whole receive-side integration is this method. {@code fitko-spring}
 * runs a background poller that downloads, decrypts and validates each
 * submission, then publishes it as an {@link SubmissionReceivedEvent};
 * {@link SubmissionEventListener} is a meta-{@code @EventListener} that also
 * filters by LeiKa key, so this method only sees Gewerbeanmeldungen. Other
 * services would go to other listeners (see {@link SubmissionAuditListener} for
 * an unfiltered one).
 *
 * <p><b>Idempotency is mandatory.</b> Delivery is at-least-once: if no
 * listener calls {@link IncomingSubmission#accept()} / {@link IncomingSubmission#reject},
 * the submission stays on the delivery service and is re-delivered - fully
 * re-downloaded and re-published - on the next poll cycle. Even after
 * {@code accept()} a crash before the server-side delete can replay it.
 * {@link ReceivedSubmissionStore#save} dedupes on submission id for that reason.
 *
 * <p>Listener methods run on the single poller thread, one submission at a
 * time. Keep them quick, or annotate with {@code @Async} / do the heavy work
 * elsewhere. {@code fitconnect.receiver.polling.submission-timeout} (default
 * 10s) abandons a submission whose listeners run too long.
 */
@Component
public class GewerbeanmeldungHandler {

    private static final Logger log = LoggerFactory.getLogger(GewerbeanmeldungHandler.class);

    private final ReceivedSubmissionStore store;

    public GewerbeanmeldungHandler(ReceivedSubmissionStore store) {
        this.store = store;
    }

    @SubmissionEventListener(serviceIds = Leistung.GEWERBEANMELDUNG_LEIKA)
    @Order(10)
    public void onGewerbeanmeldung(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();

        String xml = submission.getDataAsString();
        if (!looksLikeGewerbeanmeldung(xml)) {
            // Tell the sender why we won't process it. The submission is then
            // deleted from the delivery service - not retried. Use the
            // Problem type that fits; DataSchemaViolation says "the data
            // document doesn't match the agreed schema".
            log.warn("Rejecting submission {}: payload is not a Gewerbeanmeldung document", submission.getSubmissionId());
            submission.reject(new DataSchemaViolation());
            return;
        }

        ReceivedGewerbeanmeldung received = new ReceivedGewerbeanmeldung(
                submission.getSubmissionId(),
                submission.getCaseId(),
                submission.getDestinationId(),
                submission.getServiceType().getIdentifier(),
                submission.getDataMimeType(),
                xml,
                Instant.now());

        boolean firstTime = store.save(received);
        if (firstTime) {
            log.info("Stored Gewerbeanmeldung from submission {} (case {}), {} attachment(s)",
                    received.submissionId(), received.caseId(), submission.getAttachments().size());
        } else {
            log.info("Submission {} already processed - re-delivery, not storing again", received.submissionId());
        }

        // Accept it: FIT-Connect then deletes it from the delivery service.
        // Only do this once the data is safely persisted. Call neither
        // accept() nor reject() to have it offered again next poll
        // (fitconnect.receiver.default-outcome, default LEAVE, decides the
        // fate of a submission no listener resolved).
        submission.accept();
    }

    private static boolean looksLikeGewerbeanmeldung(String xml) {
        return xml != null && xml.contains("<Gewerbeanmeldung");
    }
}
