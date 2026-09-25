package com.gfi.ozg.ficon.processstarter;

import com.gfi.ozg.ficon.inbox.InboxSubmission;

/**
 * Everything a {@link ProcessStarter} needs to start the right downstream
 * process for one Antrag: the stored {@link InboxSubmission} - payload
 * ({@code getDataAsString()}/{@code getDataAsBytes()}), attachments, full
 * metadata, reply key, tenant, ...
 *
 * <p>The submission is already accepted on FIT-Connect by the time this is
 * built (it was persisted first, then accepted - see {@code
 * AntragRoutingListener}), so there is nothing to accept or reject here any
 * more: return a {@link StartedProcess}, or throw - see {@link ProcessStarter#start}.
 *
 * <p>{@code submission} is a managed JPA entity, and {@code start} runs
 * inside the dispatch transaction: lazy data such as {@link
 * InboxSubmission#getAttachments()} is readable there, and a {@code
 * ProcessStarter} writing through the same DataSource commits or rolls back
 * together with the submission's status (see {@code AntragDispatcher}).
 */
public record ProcessStartRequest(InboxSubmission submission) {

    /** The tenant that received the submission, e.g. {@code "101-aachen"}. */
    public String tenant() {
        return submission.getTenant();
    }
}
