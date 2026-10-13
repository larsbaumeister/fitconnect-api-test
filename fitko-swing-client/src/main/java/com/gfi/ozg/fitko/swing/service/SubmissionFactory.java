package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.DataFormat;
import dev.fitko.fitconnect.rest.model.reply.replychannel.Email;
import dev.fitko.fitconnect.rest.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.sdk.api.Addressing;
import dev.fitko.fitconnect.sdk.api.Attachment;
import dev.fitko.fitconnect.sdk.api.OutgoingSubmission;
import dev.fitko.fitconnect.sdk.api.Participant;
import dev.fitko.fitconnect.sdk.api.SubmissionData;
import dev.fitko.fitconnect.sdk.api.steps.SubmissionDataStep;
import dev.fitko.fitconnect.sdk.api.steps.SubmissionOptionalPropertiesStep;

import java.nio.file.Path;

/**
 * {@link SubmissionDraft} to the SDK's {@link OutgoingSubmission}. The SDK
 * picks the metadata version itself from the fields that are set.
 */
public final class SubmissionFactory {

    private SubmissionFactory() {
    }

    public static OutgoingSubmission build(SubmissionDraft draft) {
        require(draft.destinationId() != null, "Destination (Zustellpunkt) is missing");
        require(!SdkConfig.isBlank(draft.serviceId()), "Service id is missing");
        require(!SdkConfig.isBlank(draft.serviceName()), "Service name is missing");
        require(draft.dataSchema() != null, "Data schema is missing");
        require(!SdkConfig.isBlank(draft.payload()), "Payload is empty");

        Addressing addressing = SdkConfig.isBlank(draft.serviceRegion())
                ? Addressing.toService(draft.serviceId().trim(), draft.serviceName().trim())
                : Addressing.toService(draft.serviceId().trim(), draft.serviceName().trim(), draft.serviceRegion().trim());

        SubmissionDataStep afterRecipient = OutgoingSubmission.to(Participant.of(draft.destinationId(), addressing));
        if (draft.caseId() != null) {
            afterRecipient = afterRecipient.inCase(draft.caseId());
        }

        SubmissionOptionalPropertiesStep step = afterRecipient.setData(draft.dataFormat() == DataFormat.JSON
                ? SubmissionData.json(draft.payload(), draft.dataSchema())
                : SubmissionData.xml(draft.payload(), draft.dataSchema()));

        for (AttachmentSpec spec : draft.attachments()) {
            step = step.addAttachment(Attachment.fromFile(Path.of(spec.path), spec.mimeType));
        }
        if (!SdkConfig.isBlank(draft.replyChannelEmail())) {
            step = step.setReplyChannel(emailReplyChannel(draft.replyChannelEmail().trim()));
        }
        return step.build();
    }

    private static ReplyChannel emailReplyChannel(String address) {
        return new ReplyChannel(null, null, new Email(address, null), null, null, null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
