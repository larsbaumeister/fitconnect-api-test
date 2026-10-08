package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.inbox.InboxStatus;
import com.gfi.ozg.ficon.inbox.SubmissionInbox;
import com.gfi.ozg.ficon.processstarter.ProcessStartRejectedException;
import com.gfi.ozg.ficon.processstarter.ProcessStartRequest;
import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.ProcessStarterLookup;
import com.gfi.ozg.ficon.processstarter.ProcessStarterResolver;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles every incoming Antrag end to end, synchronously: record it in the
 * inbox, start its process through the {@link ProcessStarter} configured for
 * its (tenant, Leistung), and only then accept it on FIT-Connect - or reject
 * it, if the {@code ProcessStarter} says so. Every step is recorded in the
 * inbox before the next one runs (see {@link InboxStatus}).
 *
 * <p>No {@code serviceIds} filter on {@link SubmissionEventListener} - unlike
 * fitko-spring-sample's per-Leistung listeners, this one has to see every
 * submission to make the routing decision at all.
 *
 * <p><b>Any failure leaves the submission on the delivery service</b> - the
 * exception propagates, fitko-spring logs it and offers the submission again
 * on a later poll cycle ({@code fitconnect.receiver.polling.retry-cooldown}).
 * That next delivery continues from the recorded status instead of starting
 * over:
 * <ul>
 *   <li>{@code RECEIVED} (the start failed) - start the process again</li>
 *   <li>{@code PROCESS_STARTED} (the process runs, {@code accept()} failed) -
 *       only accept, do <em>not</em> start the process a second time</li>
 *   <li>{@code REJECTION_PENDING} ({@code reject()} failed) - reject again
 *       with the recorded problems</li>
 * </ul>
 *
 * <p><b>Idempotency:</b> the row lock taken around the process start (see
 * {@link SubmissionInbox#startProcess}) keeps two workers/replicas from
 * starting the same submission twice - provided they share one inbox
 * database, see {@link SubmissionInbox} - and a {@code ProcessStarter} writing
 * through the same DataSource commits its start together with the {@code
 * PROCESS_STARTED} status. One calling out to a remote system can't join that
 * transaction and must still be idempotent on the submission id itself.
 */
@Component
public class AntragReceiveListener {

    private static final Logger log = LoggerFactory.getLogger(AntragReceiveListener.class);

    private final ProcessStarterResolver resolver;
    private final TenantDirectory tenants;
    private final ProcessStarterLookup processStarters;
    private final SubmissionInbox inbox;

    public AntragReceiveListener(ProcessStarterResolver resolver, TenantDirectory tenants,
                                 ProcessStarterLookup processStarters, SubmissionInbox inbox) {
        this.resolver = resolver;
        this.tenants = tenants;
        this.processStarters = processStarters;
        this.inbox = inbox;
    }

    @SubmissionEventListener
    public void onAntrag(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();
        String leikaSchluessel = submission.getServiceType().getIdentifier();
        String tenant = tenants.tenantOf(submission.getDestinationId()).orElse("unknown-tenant");

        Optional<String> processStarterClass = resolver.resolveProcessStarterClassName(tenant, leikaSchluessel);
        if (processStarterClass.isEmpty()) {
            // No tenant-specific mapping and no default-process-starter-class:
            // don't take on what nothing here could process. Leave it on the
            // delivery service (LEAVE, the safe default-outcome) rather than
            // guess an implementation or silently drop it - see
            // identity-routing-trust.md's "don't guess" fallback guidance.
            // Shows up as a stuck submission in fitconnect.receive.*
            // metrics/logs until routing config catches up.
            log.warn("No ProcessStarter mapped for tenant {} / Leistung {} (submission {}) - leaving unresolved",
                    tenant, leikaSchluessel, submission.getSubmissionId());
            return;
        }

        InboxStatus status = inbox.register(submission, tenant);
        try {
            if (status == InboxStatus.RECEIVED) {
                status = startProcess(submission, tenant, processStarterClass.get());
            }
            resolve(submission, status);
        } catch (RuntimeException e) {
            inbox.recordFailure(submission.getSubmissionId(), processStarterClass.get(),
                    e.getClass().getName() + ": " + e.getMessage());
            throw e;
        }
    }

    /** @return the status after the attempt - {@code PROCESS_STARTED} or {@code REJECTION_PENDING}, normally */
    private InboxStatus startProcess(IncomingSubmission submission, String tenant, String processStarterClass) {
        UUID submissionId = submission.getSubmissionId();
        ProcessStarter processStarter = processStarters.resolve(processStarterClass);
        try {
            if (inbox.startProcess(submissionId, processStarterClass,
                    () -> processStarter.start(new ProcessStartRequest(submission, tenant)))) {
                log.info("Started process for submission {} (tenant {}) via {}", submissionId, tenant, processStarterClass);
                return InboxStatus.PROCESS_STARTED;
            }
        } catch (ProcessStartRejectedException e) {
            // The start's transaction is rolled back at this point; record
            // the decision in a fresh one before telling FIT-Connect.
            log.warn("{} rejected submission {}: {}", processStarterClass, submissionId, e.getMessage());
            if (inbox.markRejectionPending(submissionId, processStarterClass, e.getMessage(), e.getProblems())) {
                return InboxStatus.REJECTION_PENDING;
            }
        }
        // Another worker/replica moved it on first - continue from its status.
        return inbox.statusOf(submissionId);
    }

    private void resolve(IncomingSubmission submission, InboxStatus status) {
        UUID submissionId = submission.getSubmissionId();
        switch (status) {
            case PROCESS_STARTED -> {
                submission.accept();
                inbox.markAccepted(submissionId);
                log.info("Accepted submission {}", submissionId);
            }
            case REJECTION_PENDING -> {
                submission.reject(inbox.rejectionProblemsOf(submissionId));
                inbox.markRejected(submissionId);
                log.info("Rejected submission {}", submissionId);
            }
            case ACCEPTED, REJECTED ->
                    // Resolved by a concurrent delivery (another worker or
                    // replica) - nothing left to do, it is gone from the
                    // delivery service with that worker's accept/reject.
                    log.info("Submission {} is already {} - nothing to do", submissionId, status);
            case RECEIVED -> throw new IllegalStateException("Submission " + submissionId + " is still RECEIVED");
        }
    }
}
