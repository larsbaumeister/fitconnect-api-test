package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The inbox's state transitions (see {@link InboxStatus}), each in its own
 * committed transaction - so what is recorded always survives whatever
 * happens next (a failing {@code accept()}, a crash, ...), and the next
 * delivery of the same submission continues from exactly there.
 *
 * <p><b>Several replicas.</b> Any number of replicas may poll the same
 * destinations and get the same submission delivered at the same time. This
 * class makes sure its process is started only once, by two means:
 * <ul>
 *   <li><b>Registering</b> ({@link #register}): the submission id is the
 *       primary key, so of two replicas inserting the same submission only
 *       one succeeds. The other gets the constraint violation and reads the
 *       row instead. The existing row does <em>not</em> mean "someone is
 *       working on it" - a {@code RECEIVED} row may just as well be left
 *       over from an attempt that failed or crashed.</li>
 *   <li><b>Starting</b> ({@link #startProcess}): the process is started
 *       while holding a database row lock on the submission ({@code select
 *       ... for update}) and only if its status is still {@code RECEIVED}.
 *       A second replica blocks on that lock until the first one commits,
 *       then sees {@code PROCESS_STARTED} and skips the start. If the first
 *       one fails, its transaction rolls back, the status stays {@code
 *       RECEIVED}, and the second one starts the process itself.</li>
 * </ul>
 * The lock is a database row lock on purpose, not a "claimed" marker
 * written into the row: the database releases it when a replica crashes or
 * loses its connection, so a submission can never stay claimed by a replica
 * that no longer exists, and no timeout or cleanup job is needed for that.
 * The price is that a waiting replica holds a thread and a database
 * connection for as long as the {@code ProcessStarter} takes (bounded by
 * {@code fitconnect.receiver.polling.submission-timeout}), and the database's
 * lock wait timeout must be longer than that.
 *
 * <p>All of this only works if every replica uses <b>the same database</b>.
 * The default {@code jdbc:h2:file:./data/ihk-inbox} is local to each
 * instance - with more than one replica, {@code IHK_INBOX_DB_URL} must point
 * to a shared database (e.g. PostgreSQL).
 *
 * <p><b>What is recorded:</b> the status ({@link InboxStatus}) is what tells
 * whether the process was started and the submission resolved. The process
 * definition and instance id ({@code process_definition}, {@code
 * process_instance_id}) are written together with {@code PROCESS_STARTED},
 * to look up in the target system which process runs for the submission -
 * the instance id may be {@code null} if that system hands out none (see
 * {@link StartedProcess#withoutInstanceId}).
 */
@Service
public class SubmissionInbox {

    private static final Logger log = LoggerFactory.getLogger(SubmissionInbox.class);

    private final InboxSubmissionRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SubmissionInbox(InboxSubmissionRepository repository, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Stores {@code submission} as {@link InboxStatus#RECEIVED} unless it
     * already is in the inbox.
     *
     * @return the submission's current status - {@code RECEIVED} for a new
     *     one, whatever an earlier delivery got it to for a re-delivered one
     */
    public InboxStatus register(IncomingSubmission submission, String tenant) {
        UUID submissionId = submission.getSubmissionId();
        return repository.findById(submissionId).map(InboxSubmission::getStatus).orElseGet(() -> {
            try {
                transactions.executeWithoutResult(status ->
                        repository.saveAndFlush(InboxSubmission.from(submission, tenant, clock.instant())));
                return InboxStatus.RECEIVED;
            } catch (DataIntegrityViolationException e) {
                // Two workers/replicas raced on the same submission and the
                // other one committed first - the primary key caught it. Any
                // OTHER integrity violation (a value too long for its column,
                // ...) is a real failure: re-check instead of assuming.
                return repository.findById(submissionId).map(InboxSubmission::getStatus).orElseThrow(() -> e);
            }
        });
    }

    /**
     * Runs {@code start} and records its {@link StartedProcess} ({@code
     * PROCESS_STARTED}) in one transaction, holding the submission's row
     * lock throughout. A {@code ProcessStarter} writing through the same
     * DataSource (e.g. embedded Camunda 7) therefore starts the process and
     * records it atomically; if {@code start} throws, both roll back.
     *
     * @return {@code false} without calling {@code start} if the submission
     *     is no longer {@code RECEIVED} (another worker got there first)
     */
    public boolean startProcess(UUID submissionId, String processStarterClass, Supplier<StartedProcess> start) {
        Boolean started = transactions.execute(status -> {
            InboxSubmission submission = lockedRow(submissionId);
            if (submission.getStatus() != InboxStatus.RECEIVED) {
                return false;
            }
            StartedProcess process = start.get();
            if (process == null) {
                throw new IllegalStateException(processStarterClass + ".start() returned null instead of a StartedProcess");
            }
            submission.markProcessStarted(processStarterClass, process, clock.instant());
            return true;
        });
        return Boolean.TRUE.equals(started);
    }

    /**
     * Records the decision to reject, with its reason and the problems to
     * send ({@code REJECTION_PENDING}) - before {@code reject()} is called,
     * so a failed {@code reject()} is retried with the same problems.
     *
     * @return {@code false} if the submission is no longer {@code RECEIVED}
     */
    public boolean markRejectionPending(UUID submissionId, String processStarterClass, String reason,
                                        List<Problem> problems) {
        Boolean marked = transactions.execute(status -> {
            InboxSubmission submission = lockedRow(submissionId);
            if (submission.getStatus() != InboxStatus.RECEIVED) {
                return false;
            }
            submission.markRejectionPending(processStarterClass, reason, problems);
            return true;
        });
        return Boolean.TRUE.equals(marked);
    }

    /** Records a confirmed {@code accept()} ({@code PROCESS_STARTED -> ACCEPTED}). */
    public void markAccepted(UUID submissionId) {
        transactions.executeWithoutResult(status -> lockedRow(submissionId).markAccepted(clock.instant()));
    }

    /** Records a confirmed {@code reject()} ({@code REJECTION_PENDING -> REJECTED}). */
    public void markRejected(UUID submissionId) {
        transactions.executeWithoutResult(status -> lockedRow(submissionId).markRejected(clock.instant()));
    }

    /**
     * Records a failed attempt ({@code attempts}, {@code last_error}) without
     * changing the status. Never throws - it runs while another exception is
     * already on its way out, which must not be masked.
     */
    public void recordFailure(UUID submissionId, String processStarterClass, String error) {
        try {
            transactions.executeWithoutResult(status -> repository.findByIdForUpdate(submissionId)
                    .ifPresent(submission -> submission.recordFailure(processStarterClass, error)));
        } catch (RuntimeException e) {
            log.error("Could not record the failure of submission {}", submissionId, e);
        }
    }

    public InboxStatus statusOf(UUID submissionId) {
        return repository.findById(submissionId).orElseThrow(() -> notFound(submissionId)).getStatus();
    }

    public List<Problem> rejectionProblemsOf(UUID submissionId) {
        return repository.findById(submissionId).orElseThrow(() -> notFound(submissionId)).getRejectionProblems();
    }

    private InboxSubmission lockedRow(UUID submissionId) {
        return repository.findByIdForUpdate(submissionId).orElseThrow(() -> notFound(submissionId));
    }

    private static IllegalStateException notFound(UUID submissionId) {
        return new IllegalStateException("Submission " + submissionId + " is not in the inbox");
    }
}
