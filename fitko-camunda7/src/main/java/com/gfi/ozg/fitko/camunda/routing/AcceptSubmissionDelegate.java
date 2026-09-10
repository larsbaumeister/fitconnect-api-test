package com.gfi.ozg.fitko.camunda.routing;

import com.gfi.ozg.fitko.camunda.config.SubscriberClientProvider;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Step 3 of the fixed order (load &rarr; persist &rarr; <b>accept</b> &rarr;
 * decide next step): sends an {@code accept-submission} event to the FIT-Connect
 * event log. The delivery service then deletes the submission.
 *
 * <p>Only reached once {@code PersistSubmissionDelegate} has durably stored the
 * payload without throwing, so accepting here just reports the outcome to
 * FIT-Connect. What happens with the submission afterwards is decided by the
 * {@code next-step-routing} decision.
 */
@Named("acceptSubmissionDelegate")
@ApplicationScoped
@Slf4j
public class AcceptSubmissionDelegate implements JavaDelegate {

    private SubscriberClientProvider clientProvider;

    /** CDI. */
    protected AcceptSubmissionDelegate() {
    }

    @Inject
    public AcceptSubmissionDelegate(SubscriberClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public void execute(DelegateExecution execution) {
        ReceivedSubmission submission = SubmissionAccess.current(execution, clientProvider);
        log.info("Accepting submission {} (reason: {})",
                submission.getSubmissionId(), execution.getVariable("routingReason"));
        submission.acceptSubmission();
        execution.setVariable("outcome", "ACCEPTED");
    }
}
