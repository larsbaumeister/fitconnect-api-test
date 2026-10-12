package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.DataFormat;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Fully resolved values for one send - placeholders already replaced. */
public record SubmissionDraft(
        UUID destinationId,
        String serviceId,
        String serviceName,
        String serviceRegion,
        DataFormat dataFormat,
        URI dataSchema,
        String payload,
        List<AttachmentSpec> attachments,
        String replyChannelEmail,
        UUID caseId,
        String metadataVersion) {

    public SubmissionDraft {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
