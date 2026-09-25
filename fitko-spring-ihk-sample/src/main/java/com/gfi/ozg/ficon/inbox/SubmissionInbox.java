package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Persists incoming submissions - the write side of the inbox.
 *
 * <p>{@link #store} is what lets {@code AntragRoutingListener} tell a
 * <em>re-delivered</em> submission from a new one: delivery is at-least-once
 * (see fitko-spring's architecture.md, "Delivery semantics"), and the classic
 * case is a submission that was stored, but whose {@code accept()} then
 * failed on the network - FIT-Connect offers it again, and this time it
 * must only be accepted, not stored (let alone processed) a second time.
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
     * Stores {@code submission} as {@link InboxStatus#PENDING}, committed
     * before this returns.
     *
     * @return {@code true} if it was stored now, {@code false} if it already
     *     was (a re-delivery)
     * @throws RuntimeException if it could not be stored - the caller must
     *     then not accept it
     */
    public boolean store(IncomingSubmission submission, String tenant) {
        if (repository.existsById(submission.getSubmissionId())) {
            return false;
        }
        try {
            transactions.executeWithoutResult(status ->
                    repository.saveAndFlush(InboxSubmission.from(submission, tenant, clock.instant())));
            return true;
        } catch (DataIntegrityViolationException e) {
            // Two workers/replicas raced on the same submission and the other
            // one committed first - the primary key caught it. Any OTHER
            // integrity violation (a value too long for its column, ...) is a
            // real failure: re-check instead of assuming, so it can never be
            // mistaken for "already stored" and accepted without a row.
            if (repository.existsById(submission.getSubmissionId())) {
                log.debug("Submission {} was stored concurrently by another worker", submission.getSubmissionId());
                return false;
            }
            throw e;
        }
    }
}
