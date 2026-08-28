package com.gfi.ozg.fitko.receiver;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dumps everything the FIT-Connect Java SDK exposes on a decrypted
 * {@link ReceivedSubmission} - not just the subset {@link ReceivedSubmissionWriter}
 * persists as plain files - as one JSON document. Meant for inspecting the
 * full shape of what a receiver gets back, e.g. while wiring up a real
 * Fachverfahren against a new service type.
 */
final class SubmissionSnapshotWriter {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    void write(ReceivedSubmission submission, Path submissionDir) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("submissionId", submission.getSubmissionId());
        snapshot.put("caseId", submission.getCaseId());
        snapshot.put("destinationId", submission.getDestinationId());
        snapshot.put("submittedAt", submission.getSubmittedAt());
        snapshot.put("serviceType", submission.getServiceType());
        snapshot.put("region", submission.getRegion().orElse(null));
        snapshot.put("applicationDate", submission.getApplicationDate().orElse(null));
        snapshot.put("dataMimeType", submission.getDataMimeType());
        snapshot.put("dataSchemaUri", submission.getDataSchemaUri());
        snapshot.put("data", submission.getDataAsString());
        snapshot.put("attachments", summarizeAttachments(submission.getAttachments()));
        snapshot.put("metadata", submission.getMetadata());

        try {
            objectMapper.writeValue(submissionDir.resolve("full-response.json").toFile(), snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write full-response.json for submission "
                    + submission.getSubmissionId(), e);
        }
    }

    private static List<Map<String, Object>> summarizeAttachments(List<Attachment> attachments) {
        return attachments.stream().map(attachment -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("attachmentId", attachment.getAttachmentId());
            summary.put("fileName", attachment.getFileName());
            summary.put("description", attachment.getDescription());
            summary.put("mimeType", attachment.getMimeType());
            summary.put("purpose", attachment.getPurpose());
            summary.put("signature", attachment.getSignature());
            summary.put("isLargeAttachment", attachment.isLargeAttachment());
            return summary;
        }).collect(Collectors.toList());
    }
}
