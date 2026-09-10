package com.gfi.ozg.fitko.camunda.routing;

import com.gfi.ozg.fitko.camunda.config.SubscriberClientProvider;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.Variables;

import java.util.UUID;

/**
 * First step of the per-submission child process: download and decrypt the
 * submission, then expose what the rest of the process needs.
 *
 * <p>Process instance variables set here (so they can be searched later, in
 * particular {@code caseId}):
 * <ul>
 *   <li>{@code submissionId}, {@code caseId}</li>
 *   <li>{@code serviceIdentifier} / {@code serviceName} - Leika key and label</li>
 *   <li>{@code region} - destination region (ARS), if present</li>
 *   <li>{@code dataMimeType}</li>
 *   <li>{@code attachmentCount}</li>
 *   <li>{@code receivedSubmission} - the live {@link ReceivedSubmission} as a
 *       <b>transient</b> variable (this transaction only), so
 *       {@code PersistSubmissionDelegate} / {@code AcceptSubmissionDelegate} /
 *       {@code RejectSubmissionDelegate} can use it without a second download.
 *       All of them fall back to a re-fetch if it is gone.</li>
 * </ul>
 *
 * <p>The SDK validates and decrypts on {@code requestSubmission} and, in
 * TEST/STAGE/PROD, auto-rejects a submission that fails those checks.
 */
@Named("loadSubmissionDelegate")
@ApplicationScoped
@Slf4j
public class LoadSubmissionDelegate implements JavaDelegate {

    private SubscriberClientProvider clientProvider;

    /** CDI. */
    protected LoadSubmissionDelegate() {
    }

    @Inject
    public LoadSubmissionDelegate(SubscriberClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID submissionId = UUID.fromString((String) execution.getVariable("submissionRef"));
        log.info("Loading submission {}", submissionId);

        ReceivedSubmission submission = clientProvider.getClient().requestSubmission(submissionId);

        PublicService service = submission.getServiceType();
        execution.setVariable("submissionId", submissionId.toString());
        execution.setVariable("caseId", String.valueOf(submission.getCaseId()));
        execution.setVariable("serviceIdentifier", service != null ? service.getIdentifier() : null);
        execution.setVariable("serviceName", service != null ? service.getName() : null);
        execution.setVariable("region", submission.getRegion().orElse(null));
        execution.setVariable("dataMimeType", submission.getDataMimeType());
        execution.setVariable("attachmentCount",
                submission.getAttachments() != null ? submission.getAttachments().size() : 0);

        execution.setVariableLocal("receivedSubmission", Variables.untypedValue(submission, true));

        log.info("Submission {} loaded: caseId={}, service={}, region={}, mimeType={}, attachments={}",
                submissionId, execution.getVariable("caseId"), execution.getVariable("serviceIdentifier"),
                execution.getVariable("region"), execution.getVariable("dataMimeType"),
                execution.getVariable("attachmentCount"));
    }
}
