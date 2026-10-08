package com.gfi.ozg.ficon.inbox;

/**
 * Where an {@link InboxSubmission} is in its life cycle - both the process
 * start and the submission's resolution on FIT-Connect:
 *
 * <pre>
 * RECEIVED --start ok--&gt; PROCESS_STARTED --accept()--&gt; ACCEPTED
 *    |
 *    +--ProcessStartRejectedException--&gt; REJECTION_PENDING --reject()--&gt; REJECTED
 * </pre>
 *
 * The two {@code *_PENDING}-like states ({@code PROCESS_STARTED}, {@code
 * REJECTION_PENDING}) are what make a failed {@code accept()}/{@code
 * reject()} safe to repeat: the decision is already recorded, so on
 * re-delivery only the FIT-Connect call is retried, the process is not
 * started again.
 */
public enum InboxStatus {

    /** Stored, process not (yet successfully) started. Still on the delivery service. */
    RECEIVED,

    /** Process started, {@code accept()} not yet confirmed. Still on the delivery service. */
    PROCESS_STARTED,

    /** Accepted on FIT-Connect - done. */
    ACCEPTED,

    /**
     * The ProcessStarter rejected it (reason and problems recorded), {@code
     * reject()} not yet confirmed. Still on the delivery service.
     */
    REJECTION_PENDING,

    /** Rejected on FIT-Connect - done. See the rejection reason and problems. */
    REJECTED;

    /** {@code true} once FIT-Connect has confirmed the accept/reject. */
    public boolean isResolved() {
        return this == ACCEPTED || this == REJECTED;
    }
}
