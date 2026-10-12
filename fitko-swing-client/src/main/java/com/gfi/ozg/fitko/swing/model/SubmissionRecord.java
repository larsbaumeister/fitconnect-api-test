package com.gfi.ozg.fitko.swing.model;

import dev.fitko.fitconnect.api.domain.model.event.authtags.AuthenticationTags;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One submission this client sent (or tried to send). Keeps the
 * authentication tags so its status can still be checked after a restart -
 * {@code SenderClient.getSubmissionStatus} verifies them.
 */
public class SubmissionRecord {

    public String id = UUID.randomUUID().toString();
    public Instant sentAt;
    public String presetName;
    public String connectionId;
    public String connectionLabel;
    public String environment;
    public String zustellpunktId;
    public String zustellpunktLabel;
    public UUID destinationId;
    public UUID submissionId;
    public UUID caseId;
    public String serviceId;
    public String serviceName;
    public String serviceRegion;
    public DataFormat dataFormat;
    public String dataSchema;
    public String payload;
    public List<AttachmentSpec> attachments = new ArrayList<>();
    public String replyChannelEmail;
    public String metadataVersion;

    public String authTagMetadata;
    public String authTagData;
    public Map<UUID, String> authTagAttachments = new HashMap<>();

    public RecordStatus status = RecordStatus.UNKNOWN;
    public String statusIssuer;
    public Instant statusIssuedAt;
    public List<ProblemInfo> problems = new ArrayList<>();
    public Instant lastCheckedAt;
    public String lastCheckError;
    /** Why sending failed, for {@link RecordStatus#SEND_FAILED}. */
    public String error;
    public String note;

    public void applySent(SentSubmission sent) {
        submissionId = sent.getSubmissionId();
        caseId = sent.getCaseId();
        destinationId = sent.getDestinationId();
        AuthenticationTags tags = sent.getAuthenticationTags();
        if (tags != null) {
            authTagMetadata = tags.getMetadata();
            authTagData = tags.getData();
            authTagAttachments = tags.getAttachments() == null ? new HashMap<>() : new HashMap<>(tags.getAttachments());
        }
    }

    public SentSubmission toSentSubmission() {
        // Setters, not the 3-arg constructor: its order is (data, metadata, attachments), easy to mix up.
        AuthenticationTags tags = new AuthenticationTags();
        tags.setMetadata(authTagMetadata);
        tags.setData(authTagData);
        tags.setAttachments(authTagAttachments == null ? Map.of() : authTagAttachments);
        return new SentSubmission(destinationId, submissionId, caseId, tags);
    }

    public boolean wasSent() {
        return submissionId != null;
    }
}
