package com.gfi.ozg.ficon.processstarter;

import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;

/**
 * Starts the downstream process for one Antrag. Several implementations
 * coexist as Spring beans; which one handles an Antrag is configured per
 * (tenant, Leistung) under {@code antrag-routing} (see {@link
 * ProcessStarterRouter}).
 */
public interface ProcessStarter {

    /**
     * Starts the process for {@code submission} and hands over everything it
     * needs - data, metadata, attachments. This application stores none of
     * it; after the accept, the submission is gone from FIT-Connect too.
     *
     * <p>Runs inside the transaction that holds the submission's row lock and
     * records {@code PROCESS_STARTED} (see {@code SubmissionInbox#startProcess}):
     * an implementation writing through the same DataSource commits its start
     * atomically with that status. One calling a remote system cannot, and may
     * be called again for the same submission after a crash - it must be
     * idempotent on {@code submission.getSubmissionId()}.
     *
     * <p>Must not call {@code acceptSubmission()}/{@code rejectSubmission()} -
     * {@code AntragHandler} does that once the outcome is recorded.
     *
     * @param tenant the tenant whose destination the submission came in on
     * @return what was started - never {@code null}
     * @throws ProcessStartRejectedException if the Antrag can never be
     *         processed - the submission is then rejected
     * @throws RuntimeException anything else counts as transient - the
     *         submission stays on the delivery service and is tried again later
     */
    StartedProcess start(ReceivedSubmission submission, String tenant);
}
