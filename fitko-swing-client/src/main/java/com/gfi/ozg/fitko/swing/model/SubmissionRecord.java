package com.gfi.ozg.fitko.swing.model;

import dev.fitko.fitconnect.rest.model.event.authtags.AuthenticationTags;
import dev.fitko.fitconnect.rest.model.submission.SentSubmission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One submission this client sent (or tried to send). Keeps the
 * authentication tags so its status can still be checked after a restart -
 * the SDK verifies them when it reads the event log.
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
    /** The connection's own destination it was sent from; {@code null} for submissions sent before that existed. */
    public UUID fromDestinationId;
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
    /** Only set on submissions sent with the 3.x SDK, which let you pick it. */
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
        submissionId = sent.submissionId();
        caseId = sent.caseId();
        destinationId = sent.destinationId();
        fromDestinationId = sent.fromDestinationId();
        AuthenticationTags tags = sent.authenticationTags();
        if (tags != null) {
            authTagMetadata = tags.metadata();
            authTagData = tags.data();
            authTagAttachments = new HashMap<>(tags.attachments());
        }
    }

    public SentSubmission toSentSubmission() {
        AuthenticationTags tags = new AuthenticationTags(authTagMetadata, authTagData,
                authTagAttachments == null ? Map.of() : authTagAttachments);
        return SentSubmission.builder()
                .destinationId(destinationId)
                .fromDestinationId(fromDestinationId)
                .submissionId(submissionId)
                .caseId(caseId)
                .authenticationTags(tags)
                .build();
    }

    public boolean wasSent() {
        return submissionId != null;
    }
}
