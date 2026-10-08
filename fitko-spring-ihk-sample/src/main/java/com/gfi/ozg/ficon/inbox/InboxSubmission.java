package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.ficon.processstarter.StartedProcess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.metadata.Metadata;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.FitConnect;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.client.util.MetadataDeserializationHelper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A FIT-Connect submission as persisted by {@link SubmissionInbox}: a copy of
 * what {@link IncomingSubmission} carries - payload, full {@link Metadata}
 * (as JSON), the reply channel's encryption key - except the attachments,
 * which the {@code ProcessStarter} hands to the process directly. Plus which
 * process was started for it, and whether and why it was accepted or rejected
 * on FIT-Connect (see {@link InboxStatus}).
 *
 * <p>To reply later with the SDK: {@code SendableReply.forCase(getCaseId())
 * .setReplyEncryptionKey(getReplyEncryptionKey().orElseThrow())...}, sent
 * through the {@code SubscriberClient} of {@link #getDestinationId()} /
 * {@link #getTenant()}.
 *
 * <p>The submission id is the primary key - that's what makes a re-delivered
 * submission detectable (see {@link SubmissionInbox#register}). The row is
 * not itself a lock: concurrent replicas are serialized by a database row
 * lock on it, see {@link SubmissionInbox}.
 */
@Entity
@Table(name = "inbox_submission")
public class InboxSubmission implements Persistable<UUID> {

    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Id
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "destination_id", nullable = false)
    private UUID destinationId;

    @Column(name = "tenant", nullable = false, length = 100)
    private String tenant;

    @Column(name = "service_identifier", nullable = false)
    private String serviceIdentifier;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "region")
    private String region;

    @Column(name = "data_mime_type", nullable = false, length = 100)
    private String dataMimeType;

    @Column(name = "data_schema_uri", length = 1000)
    private String dataSchemaUri;

    @Lob
    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Lob
    @Column(name = "metadata_json", nullable = false)
    private String metadataJson;

    @Lob
    @Column(name = "reply_encryption_key")
    private String replyEncryptionKey;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "process_starter_class", length = 500)
    private String processStarterClass;

    @Column(name = "process_definition")
    private String processDefinition;

    @Column(name = "process_instance_id")
    private String processInstanceId;

    @Column(name = "process_started_at")
    private Instant processStartedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    /** The {@link Problem}s sent with (or to send with) {@code reject()}, as JSON. */
    @Lob
    @Column(name = "rejection_problems")
    private String rejectionProblems;

    /** When FIT-Connect confirmed the accept/reject. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** The id is assigned (the submission id), so Spring Data can't infer new-ness from it - see {@link #isNew()}. */
    @Transient
    private boolean isNew = true;

    protected InboxSubmission() {
        // for JPA
    }

    static InboxSubmission from(IncomingSubmission submission, String tenant, Instant receivedAt) {
        InboxSubmission stored = new InboxSubmission();
        stored.submissionId = submission.getSubmissionId();
        stored.caseId = submission.getCaseId();
        stored.destinationId = submission.getDestinationId();
        stored.tenant = tenant;
        stored.serviceIdentifier = submission.getServiceType().getIdentifier();
        stored.serviceName = submission.getServiceType().getName();
        stored.region = submission.getRegion().orElse(null);
        stored.dataMimeType = submission.getDataMimeType();
        URI schemaUri = submission.getDataSchemaUri();
        stored.dataSchemaUri = schemaUri == null ? null : schemaUri.toString();
        stored.payload = submission.getDataAsBytes();
        stored.metadataJson = toJson(submission.getMetadata());
        stored.replyEncryptionKey = replyEncryptionKeyOf(submission.getMetadata()).orElse(null);
        stored.applicationDate = submission.getApplicationDate().orElse(null);
        stored.receivedAt = receivedAt;
        stored.status = InboxStatus.RECEIVED;
        stored.attempts = 0;
        return stored;
    }

    // --- state transitions (SubmissionInbox only) ---------------------------

    void markProcessStarted(String processStarterClass, StartedProcess process, Instant now) {
        requireStatus(InboxStatus.RECEIVED);
        this.status = InboxStatus.PROCESS_STARTED;
        this.attempts++;
        this.processStarterClass = processStarterClass;
        this.processDefinition = process.processDefinition();
        this.processInstanceId = process.processInstanceId();
        this.processStartedAt = now;
        this.lastError = null;
    }

    void markRejectionPending(String processStarterClass, String reason, List<Problem> problems) {
        requireStatus(InboxStatus.RECEIVED);
        this.status = InboxStatus.REJECTION_PENDING;
        this.attempts++;
        this.processStarterClass = processStarterClass;
        this.rejectionReason = truncate(reason);
        this.rejectionProblems = toJson(problems);
        this.lastError = null;
    }

    void markAccepted(Instant now) {
        requireStatus(InboxStatus.PROCESS_STARTED);
        this.status = InboxStatus.ACCEPTED;
        this.resolvedAt = now;
        this.lastError = null;
    }

    void markRejected(Instant now) {
        requireStatus(InboxStatus.REJECTION_PENDING);
        this.status = InboxStatus.REJECTED;
        this.resolvedAt = now;
        this.lastError = null;
    }

    /** A failed attempt at any step - the status stays, so the next delivery continues from it. */
    void recordFailure(String processStarterClass, String error) {
        if (status == InboxStatus.RECEIVED) {
            this.attempts++;
            this.processStarterClass = processStarterClass;
        }
        this.lastError = truncate(error);
    }

    private void requireStatus(InboxStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Submission " + submissionId + " is " + status + ", not " + expected);
        }
    }

    // --- read access (ProcessStarter implementations, replies, ...) ---------

    public UUID getSubmissionId() {
        return submissionId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public String getTenant() {
        return tenant;
    }

    /** The Leistung's LeiKa-Schluessel URN - {@code IncomingSubmission.getServiceType().getIdentifier()}. */
    public String getServiceIdentifier() {
        return serviceIdentifier;
    }

    public String getServiceName() {
        return serviceName;
    }

    public Optional<String> getRegion() {
        return Optional.ofNullable(region);
    }

    public String getDataMimeType() {
        return dataMimeType;
    }

    public Optional<URI> getDataSchemaUri() {
        return Optional.ofNullable(dataSchemaUri).map(URI::create);
    }

    public byte[] getDataAsBytes() {
        return payload;
    }

    public String getDataAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** The submission's full metadata, exactly as the SDK parsed it (v1 or v2). */
    public Metadata getMetadata() {
        try {
            return MetadataDeserializationHelper.deserializeMetadata(
                    JSON, metadataJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Stored metadata of submission " + submissionId + " is unreadable", e);
        }
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    /**
     * The key to encrypt a reply with ({@code SendableReply...setReplyEncryptionKey}),
     * or empty when the sender offered no FIT-Connect reply channel.
     */
    public Optional<JWK> getReplyEncryptionKey() {
        if (replyEncryptionKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(JWK.parse(replyEncryptionKey));
        } catch (ParseException e) {
            throw new IllegalStateException("Stored reply key of submission " + submissionId + " is unreadable", e);
        }
    }

    public Optional<LocalDate> getApplicationDate() {
        return Optional.ofNullable(applicationDate);
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public InboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<String> getProcessStarterClass() {
        return Optional.ofNullable(processStarterClass);
    }

    /** Which process was started (a {@link StartedProcess#processDefinition()}) - set from {@code PROCESS_STARTED} on. */
    public Optional<String> getProcessDefinition() {
        return Optional.ofNullable(processDefinition);
    }

    /** The started instance's id in the target system, if it provides one - set from {@code PROCESS_STARTED} on. */
    public Optional<String> getProcessInstanceId() {
        return Optional.ofNullable(processInstanceId);
    }

    /** When the process was started - set from {@code PROCESS_STARTED} on. */
    public Optional<Instant> getProcessStartedAt() {
        return Optional.ofNullable(processStartedAt);
    }

    /** Why the ProcessStarter rejected it - set from {@code REJECTION_PENDING} on. */
    public Optional<String> getRejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    /** The problems sent to FIT-Connect with {@code reject()} - set from {@code REJECTION_PENDING} on. */
    public List<Problem> getRejectionProblems() {
        if (rejectionProblems == null) {
            return List.of();
        }
        try {
            return JSON.readValue(rejectionProblems, new TypeReference<List<Problem>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored rejection problems of submission " + submissionId + " are unreadable", e);
        }
    }

    /** When FIT-Connect confirmed the accept/reject - set once {@code ACCEPTED}/{@code REJECTED}. */
    public Optional<Instant> getResolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }

    @Override
    public UUID getId() {
        return submissionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private static Optional<String> replyEncryptionKeyOf(Metadata metadata) {
        return Optional.ofNullable(metadata)
                .map(Metadata::getReplyChannel)
                .map(ReplyChannel::getFitConnect)
                .map(FitConnect::getEncryptionPublicKey)
                .map(key -> key.toJwk().toJSONString());
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
