package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.ficon.processstarter.StartedProcess;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The inbox's state transitions (see {@link InboxStatus}), each in its own
 * committed transaction - so what is recorded survives whatever happens next
 * (a failing {@code acceptSubmission()}, a crash, ...), and the next delivery
 * of the same submission continues from exactly there.
 *
 * <p><b>Several replicas.</b> Every replica polls the same destinations and
 * can get the same submission at the same time. Its process is started only
 * once, through the shared PostgreSQL database:
 * <ul>
 *   <li><b>Registering</b> ({@link #register}): the submission id is the
 *       primary key, so of two replicas inserting the same submission only
 *       one succeeds. The other one gets the constraint violation and reads
 *       the row instead. An existing row does <em>not</em> mean "someone is
 *       working on it" - a {@code RECEIVED} row may just as well be left
 *       over from an attempt that failed or crashed.</li>
 *   <li><b>Starting</b> ({@link #startProcess}): the process is started
 *       while holding a row lock on the submission ({@code select ... for
 *       update skip locked}), and only if its status is still {@code
 *       RECEIVED}. A second replica does not wait for that lock: it gets
 *       nothing back and skips the submission until its next poll. By then
 *       the first one has committed {@code PROCESS_STARTED} (and the second
 *       one only accepts), or rolled back (and the second one starts the
 *       process itself).</li>
 * </ul>
 * The lock is a database row lock on purpose, not a "claimed" marker written
 * into the row: PostgreSQL releases it when a replica crashes or loses its
 * connection, so a submission can never stay claimed by a replica that no
 * longer exists, and no timeout or cleanup job is needed for that. Skipping
 * instead of waiting keeps a slow {@code ProcessStarter} on one replica from
 * stalling the single poller thread of the others.
 *
 * <p>Once {@code PROCESS_STARTED} or {@code REJECTION_PENDING} is committed,
 * two replicas may both call {@code acceptSubmission()}/{@code
 * rejectSubmission()} for the same submission. That is harmless - the
 * process is still started only once, and whichever call FIT-Connect does not
 * take fails like any other failure and is not tried again once the
 * submission is gone from the delivery service.
 *
 * <p><b>What is recorded:</b> the status tells whether the process was
 * started and the submission resolved. The process definition and instance
 * id are written together with {@code PROCESS_STARTED}, to look up in the
 * target system which process runs for the submission - the instance id may
 * be {@code null} (see {@link StartedProcess#withoutInstanceId}).
 */
@Service
public class SubmissionInbox {

    private static final Logger log = LoggerFactory.getLogger(SubmissionInbox.class);

    private final InboxSubmissionRepository repository;
    private final TransactionTemplate transactions;

    public SubmissionInbox(InboxSubmissionRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /**
     * Stores {@code submission} as {@code RECEIVED} unless it already is in the inbox.
     *
     * @return the submission's current status - {@code RECEIVED} for a new
     *     one, whatever an earlier attempt got it to for a re-delivered one
     */
    public InboxStatus register(ReceivedSubmission submission, String tenant) {
        UUID submissionId = submission.getSubmissionId();
        return repository.findById(submissionId).map(InboxSubmission::getStatus).orElseGet(() -> {
            try {
                transactions.executeWithoutResult(tx ->
                        repository.saveAndFlush(InboxSubmission.from(submission, tenant, Instant.now())));
                return InboxStatus.RECEIVED;
            } catch (DataIntegrityViolationException e) {
                // Another replica inserted it first. Any other integrity
                // violation (a value too long, ...) is a real failure, hence
                // the re-check instead of assuming.
                return repository.findById(submissionId).map(InboxSubmission::getStatus).orElseThrow(() -> e);
            }
        });
    }

    /**
     * Runs {@code start} and records its result as {@code PROCESS_STARTED},
     * in one transaction holding the submission's row lock. If {@code start}
     * throws, both roll back and the status stays {@code RECEIVED}.
     *
     * @return empty if another replica holds the lock (skip the submission
     *     for now); otherwise the status afterwards - {@code PROCESS_STARTED},
     *     or the status another replica already got it to ({@code start} is
     *     then not called)
     */
    public Optional<InboxStatus> startProcess(UUID submissionId, String processStarterClass,
                                              Supplier<StartedProcess> start) {
        return transactions.execute(tx -> repository.lockUnlessLocked(submissionId).map(row -> {
            if (row.getStatus() == InboxStatus.RECEIVED) {
                StartedProcess process = start.get();
                if (process == null) {
                    throw new IllegalStateException(processStarterClass + ".start() returned null");
                }
                row.markProcessStarted(processStarterClass, process, Instant.now());
            }
            return row.getStatus();
        }));
    }

    /**
     * Records the decision to reject, with reason and problems, before {@code
     * rejectSubmission()} is called - so a failed rejection is repeated with
     * the same problems.
     *
     * @return like {@link #startProcess}: empty if locked by another replica,
     *     else {@code REJECTION_PENDING} or the status another replica got it to
     */
    public Optional<InboxStatus> markRejectionPending(UUID submissionId, String processStarterClass, String reason,
                                                      List<Problem> problems) {
        return transactions.execute(tx -> repository.lockUnlessLocked(submissionId).map(row -> {
            if (row.getStatus() == InboxStatus.RECEIVED) {
                row.markRejectionPending(processStarterClass, reason, problems);
            }
            return row.getStatus();
        }));
    }

    /** {@code PROCESS_STARTED -> ACCEPTED}, after a successful {@code acceptSubmission()}. */
    public void markAccepted(UUID submissionId) {
        transactions.executeWithoutResult(tx -> row(submissionId).markAccepted(Instant.now()));
    }

    /** {@code REJECTION_PENDING -> REJECTED}, after a successful {@code rejectSubmission()}. */
    public void markRejected(UUID submissionId) {
        transactions.executeWithoutResult(tx -> row(submissionId).markRejected(Instant.now()));
    }

    /**
     * Records a failed attempt ({@code attempts}, {@code last_error}) without
     * changing the status. Never throws - it runs while another exception is
     * on its way out, which must not be masked.
     */
    public void recordFailure(UUID submissionId, String processStarterClass, String error) {
        try {
            transactions.executeWithoutResult(tx -> repository.findById(submissionId)
                    .ifPresent(row -> row.recordFailure(processStarterClass, error)));
        } catch (RuntimeException e) {
            log.error("Could not record the failure of submission {}", submissionId, e);
        }
    }

    public List<Problem> rejectionProblemsOf(UUID submissionId) {
        return row(submissionId).getRejectionProblems();
    }

    private InboxSubmission row(UUID submissionId) {
        return repository.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission " + submissionId + " is not in the inbox"));
    }
}
