package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.ficon.processstarter.StartedProcess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.domain.model.metadata.Metadata;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.FitConnect;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.client.util.MetadataDeserializationHelper;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A FIT-Connect submission as persisted by {@link SubmissionInbox} - a copy
 * of everything {@link IncomingSubmission} carries, so the submission can be
 * worked on (process start, replies) long after it left the delivery
 * service: payload, full {@link Metadata} (as JSON), attachments, and the
 * reply channel's encryption key. Plus the dispatch state {@link
 * AntragDispatcher} maintains.
 *
 * <p>To reply later with the SDK: {@code SendableReply.forCase(getCaseId())
 * .setReplyEncryptionKey(getReplyEncryptionKey().orElseThrow())...}, sent
 * through the {@code SubscriberClient} of {@link #getDestinationId()} /
 * {@link #getTenant()}.
 *
 * <p>The submission id is the primary key - that's what makes a re-delivered
 * submission detectable (see {@link SubmissionInbox#store}).
 */
@Entity
@Table(name = "inbox_submission")
public class InboxSubmission implements Persistable<UUID> {

    private static final ObjectMapper METADATA_MAPPER =
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

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "process_starter_class", length = 500)
    private String processStarterClass;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "process_definition")
    private String processDefinition;

    @Column(name = "process_instance_id")
    private String processInstanceId;

    @Column(name = "process_started_at")
    private Instant processStartedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<InboxAttachment> attachments = new ArrayList<>();

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
        stored.status = InboxStatus.PENDING;
        stored.attempts = 0;
        stored.nextAttemptAt = receivedAt;
        List<dev.fitko.fitconnect.api.domain.model.attachment.Attachment> sdkAttachments = submission.getAttachments();
        for (int i = 0; i < sdkAttachments.size(); i++) {
            stored.attachments.add(InboxAttachment.from(stored, i, sdkAttachments.get(i)));
        }
        return stored;
    }

    // --- dispatch state transitions (AntragDispatcher only) -----------------

    boolean isDue(Instant now) {
        return status == InboxStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    void markStarted(String processStarterClass, StartedProcess process, Instant now) {
        this.status = InboxStatus.STARTED;
        this.attempts++;
        this.processStarterClass = processStarterClass;
        this.processDefinition = process.processDefinition();
        this.processInstanceId = process.processInstanceId();
        this.processStartedAt = now;
        this.processedAt = now;
        this.lastError = null;
    }

    void markRejected(String processStarterClass, String reason, Instant now) {
        this.status = InboxStatus.REJECTED;
        this.attempts++;
        this.processStarterClass = processStarterClass;
        this.processedAt = now;
        this.lastError = truncate(reason);
    }

    /**
     * Records a failed attempt: back to {@code PENDING} with {@code
     * nextAttemptAt = now + retryDelay}, or {@code FAILED} once {@code
     * maxAttempts} is reached.
     */
    void recordFailure(String processStarterClass, String error, Instant now, Duration retryDelay, int maxAttempts) {
        this.attempts++;
        this.processStarterClass = processStarterClass;
        this.lastError = truncate(error);
        if (attempts >= maxAttempts) {
            this.status = InboxStatus.FAILED;
            this.processedAt = now;
        } else {
            this.nextAttemptAt = now.plus(retryDelay);
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
                    METADATA_MAPPER, metadataJson.getBytes(StandardCharsets.UTF_8));
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

    public List<InboxAttachment> getAttachments() {
        return List.copyOf(attachments);
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

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<String> getProcessStarterClass() {
        return Optional.ofNullable(processStarterClass);
    }

    /** When the submission reached its final status ({@code STARTED}, {@code REJECTED} or {@code FAILED}). */
    public Optional<Instant> getProcessedAt() {
        return Optional.ofNullable(processedAt);
    }

    /** Which process was started (a {@link StartedProcess#processDefinition()}) - only when {@code STARTED}. */
    public Optional<String> getProcessDefinition() {
        return Optional.ofNullable(processDefinition);
    }

    /** The started instance's id in the target system - only when {@code STARTED} and the system provides one. */
    public Optional<String> getProcessInstanceId() {
        return Optional.ofNullable(processInstanceId);
    }

    /** When the process was started - only when {@code STARTED}. */
    public Optional<Instant> getProcessStartedAt() {
        return Optional.ofNullable(processStartedAt);
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

    private static String toJson(Metadata metadata) {
        try {
            return METADATA_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize submission metadata", e);
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
