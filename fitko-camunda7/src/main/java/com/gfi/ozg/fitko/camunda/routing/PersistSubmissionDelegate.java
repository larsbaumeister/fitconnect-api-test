package com.gfi.ozg.fitko.camunda.routing;

import com.gfi.ozg.fitko.camunda.config.SubscriberClientProvider;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

import java.util.List;

/**
 * Step 2 of the fixed order (load &rarr; <b>persist</b> &rarr; accept &rarr;
 * decide next step): hand the decrypted submission (data + attachments) to the
 * Fachverfahren and store it durably <b>before</b> the accept event is sent -
 * once accepted, the submission is deleted on the delivery service and cannot
 * be fetched again.
 *
 * <p><b>On failure this delegate must throw.</b> {@code AcceptSubmission} is the
 * next task, so a thrown exception means the submission is never accepted: it
 * stays on the delivery service, this child instance's job retries, and a later
 * poll cycle starts a fresh instance for it. That is the "if persist fails, do
 * not accept" rule.
 *
 * <p>Placeholder: this only reads the payload and logs its size. Replace the
 * body with the real, idempotent handover (DB write, file store, downstream
 * API call, ...).
 */
@Named("persistSubmissionDelegate")
@ApplicationScoped
@Slf4j
public class PersistSubmissionDelegate implements JavaDelegate {

    private SubscriberClientProvider clientProvider;

    /** CDI. */
    protected PersistSubmissionDelegate() {
    }

    @Inject
    public PersistSubmissionDelegate(SubscriberClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public void execute(DelegateExecution execution) {
        ReceivedSubmission submission = SubmissionAccess.current(execution, clientProvider);

        byte[] data = submission.getDataAsBytes();
        List<Attachment> attachments = submission.getAttachments();
        int attachmentCount = attachments != null ? attachments.size() : 0;

        // TODO hand `data` (mime type submission.getDataMimeType()) and the
        // attachments to the Fachverfahren and commit them durably here.
        log.info("Persisting submission {} for case {}: {} bytes of {}, {} attachment(s)",
                submission.getSubmissionId(), execution.getVariable("caseId"),
                data != null ? data.length : 0, submission.getDataMimeType(), attachmentCount);

        execution.setVariable("persisted", true);
    }
}
