package dev.fitko.fitconnect.samples.sender;

import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.samples.common.cli.ArgumentReader;
import dev.fitko.fitconnect.samples.common.cli.CliUsageException;

import java.util.UUID;

/**
 * One of the mutually exclusive reply channels a sender can request, parsed
 * from CLI flags and turned into an SDK {@link ReplyChannel}. FIT-Connect's
 * metadata schema allows at most one reply channel per submission.
 */
final class ReplyChannelSpec {

    private enum Kind { EMAIL, ELSTER, ID_BUND_DE_MAILBOX }

    private final Kind kind;
    private final String email;
    private final String elsterAccountId;
    private final String elsterDeliveryTicket;
    private final String elsterReference;
    private final UUID bundIdMailboxUuid;

    private ReplyChannelSpec(Kind kind, String email, String elsterAccountId, String elsterDeliveryTicket,
                              String elsterReference, UUID bundIdMailboxUuid) {
        this.kind = kind;
        this.email = email;
        this.elsterAccountId = elsterAccountId;
        this.elsterDeliveryTicket = elsterDeliveryTicket;
        this.elsterReference = elsterReference;
        this.bundIdMailboxUuid = bundIdMailboxUuid;
    }

    /** Returns {@code null} if no reply channel flag was given. */
    static ReplyChannelSpec parse(ArgumentReader reader) {
        String email = reader.get("reply-channel-email").orElse(null);
        String elsterAccountId = reader.get("reply-channel-elster-account-id").orElse(null);
        String elsterDeliveryTicket = reader.get("reply-channel-elster-delivery-ticket").orElse(null);
        String elsterReference = reader.get("reply-channel-elster-reference").orElse(null);
        UUID bundIdMailboxUuid = reader.getUuid("reply-channel-bundid-mailbox").orElse(null);

        int chosen = (email != null ? 1 : 0) + (elsterAccountId != null ? 1 : 0) + (bundIdMailboxUuid != null ? 1 : 0);
        if (chosen > 1) {
            throw new CliUsageException("Specify at most one of --reply-channel-email, "
                    + "--reply-channel-elster-account-id, --reply-channel-bundid-mailbox");
        }
        if (email != null) {
            return new ReplyChannelSpec(Kind.EMAIL, email, null, null, null, null);
        }
        if (elsterAccountId != null) {
            return new ReplyChannelSpec(Kind.ELSTER, null, elsterAccountId, elsterDeliveryTicket, elsterReference, null);
        }
        if (bundIdMailboxUuid != null) {
            return new ReplyChannelSpec(Kind.ID_BUND_DE_MAILBOX, null, null, null, null, bundIdMailboxUuid);
        }
        if (elsterDeliveryTicket != null || elsterReference != null) {
            throw new CliUsageException("--reply-channel-elster-delivery-ticket and --reply-channel-elster-reference "
                    + "require --reply-channel-elster-account-id");
        }
        return null;
    }

    ReplyChannel toReplyChannel() {
        switch (kind) {
            case EMAIL:
                return ReplyChannel.ofEmail(email);
            case ELSTER:
                return ReplyChannel.ofElster(elsterAccountId, elsterDeliveryTicket, elsterReference);
            case ID_BUND_DE_MAILBOX:
                return ReplyChannel.ofIdBundDeMailbox(bundIdMailboxUuid);
            default:
                throw new IllegalStateException("Unhandled reply channel kind: " + kind);
        }
    }
}
