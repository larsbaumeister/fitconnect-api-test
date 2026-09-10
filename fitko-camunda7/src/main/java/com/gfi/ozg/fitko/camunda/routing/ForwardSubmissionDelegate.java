package com.gfi.ozg.fitko.camunda.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * {@code nextStep == "FORWARD"}: hand the (already persisted and accepted)
 * submission on to the internal system / queue named by {@code nextStepTarget},
 * as decided by {@code next-step-routing} from the service (Leika key).
 *
 * <p>Placeholder: logs the hand-off. The real implementation would push to that
 * target - a message queue, a REST call, starting another Camunda process,
 * writing a task row, ... It works from the process variables and whatever
 * {@code PersistSubmissionDelegate} stored; it must not call the FIT-Connect
 * SDK, because the submission is already accepted and gone from the service.
 */
@Named("forwardSubmissionDelegate")
@ApplicationScoped
@Slf4j
public class ForwardSubmissionDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        Object target = execution.getVariable("nextStepTarget");
        log.info("Forwarding submission {} (case {}, service {}) to '{}'",
                execution.getVariable("submissionId"), execution.getVariable("caseId"),
                execution.getVariable("serviceIdentifier"), target);
        execution.setVariable("nextStepOutcome", "FORWARDED");
    }
}
