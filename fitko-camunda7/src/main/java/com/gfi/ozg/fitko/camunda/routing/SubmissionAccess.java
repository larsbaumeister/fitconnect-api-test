package com.gfi.ozg.fitko.camunda.routing;

import com.gfi.ozg.fitko.camunda.config.SubscriberClientProvider;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.camunda.bpm.engine.delegate.DelegateExecution;

import java.util.UUID;

/**
 * Resolves the {@link ReceivedSubmission} for the current sub-process
 * instance: the transient object {@code LoadSubmissionDelegate} stashed if it
 * is still there, otherwise a fresh download by {@code submissionRef}.
 */
final class SubmissionAccess {

    private SubmissionAccess() {
    }

    static ReceivedSubmission current(DelegateExecution execution, SubscriberClientProvider clientProvider) {
        Object transientSubmission = execution.getVariable("receivedSubmission");
        if (transientSubmission instanceof ReceivedSubmission received) {
            return received;
        }
        UUID submissionId = UUID.fromString((String) execution.getVariable("submissionRef"));
        return clientProvider.getClient().requestSubmission(submissionId);
    }
}
