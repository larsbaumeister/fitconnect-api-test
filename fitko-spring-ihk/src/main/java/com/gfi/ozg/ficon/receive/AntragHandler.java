package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.inbox.InboxStatus;
import com.gfi.ozg.ficon.inbox.SubmissionInbox;
import com.gfi.ozg.ficon.processstarter.ProcessStartRejectedException;
import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.ProcessStarterRouter;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles one downloaded submission: record it in the inbox, start its
 * process through the {@link ProcessStarter} routed to it, and only then
 * accept it on FIT-Connect - or reject it, if the {@code ProcessStarter} says
 * so. Every step is recorded before the next one runs (see {@link InboxStatus}).
 *
 * <p><b>Any failure leaves the submission on the delivery service</b>: the
 * exception propagates to {@link AntragPoller}, which tries it again later.
 * That next attempt continues from the recorded status instead of starting
 * over:
 * <ul>
 *   <li>{@code RECEIVED} (the start failed) - start the process again</li>
 *   <li>{@code PROCESS_STARTED} ({@code acceptSubmission()} failed) - only
 *       accept, do <em>not</em> start the process a second time</li>
 *   <li>{@code REJECTION_PENDING} ({@code rejectSubmission()} failed) -
 *       reject again with the recorded problems</li>
 * </ul>
 *
 * <p>How this stays correct with several replicas is described on {@link
 * SubmissionInbox}.
 *
 * <p>Known gap: if {@code acceptSubmission()}/{@code rejectSubmission()}
 * succeeds but recording {@code ACCEPTED}/{@code REJECTED} fails (database
 * down at exactly that moment), the row stays {@code PROCESS_STARTED}/{@code
 * REJECTION_PENDING} although FIT-Connect has resolved the submission.
 */
@Component
public class AntragHandler {

    private static final Logger log = LoggerFactory.getLogger(AntragHandler.class);

    private final ProcessStarterRouter router;
    private final SubmissionInbox inbox;

    public AntragHandler(ProcessStarterRouter router, SubmissionInbox inbox) {
        this.router = router;
        this.inbox = inbox;
    }

    /**
     * @param tenant the tenant whose destination {@code submission} came in on
     * @throws IllegalStateException if no {@code ProcessStarter} is configured
     *     for it - it is then left untouched on the delivery service
     */
    public void handle(ReceivedSubmission submission, String tenant) {
        UUID submissionId = submission.getSubmissionId();
        String leistung = submission.getServiceType().getIdentifier();
        // Don't take on what nothing here could process - not even into the inbox.
        String processStarterClass = router.processStarterClassFor(tenant, leistung)
                .orElseThrow(() -> new IllegalStateException("No ProcessStarter configured for tenant " + tenant
                        + " / Leistung " + leistung + " - leaving submission " + submissionId + " on the delivery service"));

        InboxStatus registered = inbox.register(submission, tenant);
        try {
            Optional<InboxStatus> status = registered == InboxStatus.RECEIVED
                    ? startProcess(submission, tenant, processStarterClass)
                    : Optional.of(registered);
            status.ifPresentOrElse(s -> resolve(submission, s),
                    () -> log.info("Submission {} is being handled by another replica - skipped", submissionId));
        } catch (RuntimeException e) {
            inbox.recordFailure(submissionId, processStarterClass, e.getClass().getName() + ": " + e.getMessage());
            throw e;
        }
    }

    private Optional<InboxStatus> startProcess(ReceivedSubmission submission, String tenant, String processStarterClass) {
        UUID submissionId = submission.getSubmissionId();
        ProcessStarter processStarter = router.processStarter(processStarterClass);
        try {
            Optional<InboxStatus> status = inbox.startProcess(submissionId, processStarterClass,
                    () -> processStarter.start(submission, tenant));
            if (status.isPresent()) {
                log.info("Submission {} (tenant {}): process started via {}", submissionId, tenant, processStarterClass);
            }
            return status;
        } catch (ProcessStartRejectedException e) {
            // The start's transaction is rolled back; record the decision in a new one.
            log.warn("Submission {}: {} rejected it: {}", submissionId, processStarterClass, e.getMessage());
            return inbox.markRejectionPending(submissionId, processStarterClass, e.getMessage(), e.getProblems());
        }
    }

    private void resolve(ReceivedSubmission submission, InboxStatus status) {
        UUID submissionId = submission.getSubmissionId();
        switch (status) {
            case PROCESS_STARTED -> {
                submission.acceptSubmission();
                inbox.markAccepted(submissionId);
                log.info("Submission {} accepted", submissionId);
            }
            case REJECTION_PENDING -> {
                submission.rejectSubmission(inbox.rejectionProblemsOf(submissionId));
                inbox.markRejected(submissionId);
                log.info("Submission {} rejected", submissionId);
            }
            case ACCEPTED, REJECTED -> log.info("Submission {} is already {} - nothing to do", submissionId, status);
            case RECEIVED -> throw new IllegalStateException("Submission " + submissionId + " is still RECEIVED");
        }
    }
}
