package com.gfi.ozg.fitko.swing.model;

import dev.fitko.fitconnect.rest.model.event.EventState;

/**
 * Status of a {@link SubmissionRecord}: the SDK's {@link EventState}s plus
 * {@link #SEND_FAILED} for a submission that never left this machine and
 * {@link #UNKNOWN} for one whose status was never fetched.
 */
public enum RecordStatus {
    INCOMPLETE,
    SUBMITTED,
    NOTIFIED,
    FORWARDED,
    ACCEPTED,
    REJECTED,
    DELETED,
    SEND_FAILED,
    UNKNOWN;

    public static RecordStatus of(EventState state) {
        if (state == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(state.name());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** No further status change is expected - the poller stops looking at it. */
    public boolean isFinal() {
        return this == ACCEPTED || this == REJECTED || this == DELETED || this == SEND_FAILED;
    }

    /** Sent and waiting for the receiver to accept or reject it. */
    public boolean isPending() {
        return !isFinal();
    }
}
