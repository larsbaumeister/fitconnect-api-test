package com.gfi.ozg.ficon.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code inbox_submission}: which {@link ReceivedSubmission} it is
 * and where it came in - no content, this application only hands the
 * submission over - plus which process was started for it, and whether and
 * why it was accepted or rejected (see {@link InboxStatus}).
 *
 * <p>The submission id is the primary key, which makes a re-delivered
 * submission detectable. The row is not itself a lock - replicas are kept
 * apart by a database row lock on it, see {@link SubmissionInbox}.
 */
@Entity
@Table(name = "inbox_submission")
public class InboxSubmission implements Persistable<UUID> {

    // Jackson 2, because the SDK's Problem classes are annotated for it.
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_TEXT = 2000;

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

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_TEXT)
    private String lastError;

    @Column(name = "process_starter_class", length = 500)
    private String processStarterClass;

    @Column(name = "process_definition")
    private String processDefinition;

    @Column(name = "process_instance_id")
    private String processInstanceId;

    @Column(name = "process_started_at")
    private Instant processStartedAt;

    @Column(name = "rejection_reason", length = MAX_TEXT)
    private String rejectionReason;

    /** The {@link Problem}s sent (or to send) with the rejection, as JSON. */
    @Column(name = "rejection_problems")
    private String rejectionProblems;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** The id is assigned, so Spring Data can't tell new from existing by it - see {@link #isNew()}. */
    @Transient
    private boolean isNew = true;

    protected InboxSubmission() {
        // for JPA
    }

    static InboxSubmission from(ReceivedSubmission submission, String tenant, Instant receivedAt) {
        InboxSubmission row = new InboxSubmission();
        row.submissionId = submission.getSubmissionId();
        row.caseId = submission.getCaseId();
        row.destinationId = submission.getDestinationId();
        row.tenant = tenant;
        row.serviceIdentifier = submission.getServiceType().getIdentifier();
        row.receivedAt = receivedAt;
        row.status = InboxStatus.RECEIVED;
        return row;
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
        try {
            this.rejectionProblems = JSON.writeValueAsString(problems);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the rejection problems", e);
        }
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

    /** A failed attempt at any step - the status stays, so the next attempt continues from it. */
    void recordFailure(String processStarterClass, String error) {
        if (status.isResolved()) {
            return; // e.g. a second replica's accept() after this one's - nothing failed here
        }
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

    // --- read access ---------------------------------------------------------

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

    /** The Leistung's LeiKa-Schluessel URN. */
    public String getServiceIdentifier() {
        return serviceIdentifier;
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

    public Optional<String> getProcessDefinition() {
        return Optional.ofNullable(processDefinition);
    }

    public Optional<String> getProcessInstanceId() {
        return Optional.ofNullable(processInstanceId);
    }

    public Optional<Instant> getProcessStartedAt() {
        return Optional.ofNullable(processStartedAt);
    }

    public Optional<String> getRejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

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

    /** When FIT-Connect confirmed the accept/reject. */
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

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }
}
