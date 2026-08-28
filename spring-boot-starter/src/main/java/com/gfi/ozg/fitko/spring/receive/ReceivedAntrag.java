package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.event.problems.other.TechnicalError;
import dev.fitko.fitconnect.api.domain.model.metadata.Metadata;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A downloaded, decrypted, and locally validated submission, delivered to
 * application code via {@link AntragReceivedEvent}.
 *
 * <p>Exactly one of {@link #accept()}/{@link #reject} may be called, and at
 * most once - further calls throw {@link IllegalStateException}. Call
 * neither to leave the submission on the delivery service for now (it will
 * be offered again on the next poll); {@link
 * com.gfi.ozg.fitko.spring.FitConnectProperties.Receiver#getDefaultOutcome()}
 * decides what happens to a submission no listener resolved.
 */
public final class ReceivedAntrag {

    private final ReceivedSubmission delegate;
    private final AtomicBoolean resolved = new AtomicBoolean(false);

    ReceivedAntrag(ReceivedSubmission delegate) {
        this.delegate = delegate;
    }

    /** Accepts the submission; it is then deleted from the delivery service. */
    public void accept() {
        markResolved("accept");
        delegate.acceptSubmission();
    }

    /** Rejects the submission with the given reasons; it is then deleted from the delivery service. */
    public void reject(List<Problem> problems) {
        markResolved("reject");
        delegate.rejectSubmission(problems);
    }

    public void reject(Problem... problems) {
        reject(List.of(problems));
    }

    public UUID getSubmissionId() {
        return delegate.getSubmissionId();
    }

    public UUID getCaseId() {
        return delegate.getCaseId();
    }

    public UUID getDestinationId() {
        return delegate.getDestinationId();
    }

    public String getDataAsString() {
        return delegate.getDataAsString();
    }

    public byte[] getDataAsBytes() {
        return delegate.getDataAsBytes();
    }

    public String getDataMimeType() {
        return delegate.getDataMimeType();
    }

    public PublicService getServiceType() {
        return delegate.getServiceType();
    }

    public Optional<String> getRegion() {
        return delegate.getRegion();
    }

    public List<Attachment> getAttachments() {
        return delegate.getAttachments();
    }

    public Metadata getMetadata() {
        return delegate.getMetadata();
    }

    public Optional<LocalDate> getApplicationDate() {
        return delegate.getApplicationDate();
    }

    public boolean isResolved() {
        return resolved.get();
    }

    /** Applies {@code outcome} if no listener already called {@link #accept()}/{@link #reject}. */
    void applyIfUnresolved(DefaultOutcome outcome) {
        switch (outcome) {
            case ACCEPT:
                if (!resolved.get()) {
                    accept();
                }
                break;
            case REJECT:
                if (!resolved.get()) {
                    reject(new TechnicalError());
                }
                break;
            case LEAVE:
            default:
                // nothing to do - left on the delivery service for the next poll
        }
    }

    private void markResolved(String action) {
        if (!resolved.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Submission " + delegate.getSubmissionId() + " was already accepted/rejected; cannot " + action + " again");
        }
    }
}
