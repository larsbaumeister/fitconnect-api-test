package com.gfi.ozg.fitko.spring.receive;

/**
 * What {@link SubmissionPollingService} does with a downloaded {@link IncomingSubmission}
 * after every {@code @EventListener} for {@link SubmissionReceivedEvent} has
 * returned, if none of them already called {@link IncomingSubmission#accept()} or
 * {@link IncomingSubmission#reject}.
 */
public enum DefaultOutcome {

    /**
     * Leave the submission on the delivery service, unresolved. The safe
     * default: nothing is deleted server-side, so it will be picked up again
     * on the next poll.
     */
    LEAVE,

    /** Accept every submission no listener explicitly resolved. */
    ACCEPT,

    /** Reject every submission no listener explicitly resolved, as a generic {@code TechnicalError}. */
    REJECT
}
