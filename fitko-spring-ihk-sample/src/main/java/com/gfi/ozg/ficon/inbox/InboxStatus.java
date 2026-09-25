package com.gfi.ozg.ficon.inbox;

/**
 * Where an {@link InboxSubmission} is in its life cycle. Every submission is
 * already accepted on FIT-Connect by the time it has a row - these states are
 * purely about the downstream process start.
 */
public enum InboxStatus {

    /** Stored, not yet (successfully) handed to its ProcessStarter - picked up by {@link AntragDispatcher}. */
    PENDING,

    /** The ProcessStarter returned normally - done. */
    STARTED,

    /**
     * The ProcessStarter threw {@code ProcessStartRejectedException} - done,
     * not retried. The applicant still needs to be told, e.g. via a reply
     * (see {@link InboxSubmission#getReplyEncryptionKey()}).
     */
    REJECTED,

    /** Every attempt failed ({@code antrag-dispatch.max-attempts}) - needs manual intervention. */
    FAILED
}
