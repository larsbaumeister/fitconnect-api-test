package com.gfi.ozg.fitko.camunda.poll;

import com.gfi.ozg.fitko.camunda.config.FitConnectCamundaConfig;
import com.gfi.ozg.fitko.camunda.config.SubscriberClientProvider;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The "poll new submissions" service task of the {@code fitconnect-poll}
 * dispatcher process.
 *
 * <p>Lists the submission ids currently available for the configured
 * destination and publishes them as:
 * <ul>
 *   <li>{@code submissionRefs} - {@code ArrayList&lt;String&gt;}, the
 *       collection the multi-instance call activity iterates over;</li>
 *   <li>{@code submissionCount} - its size.</li>
 * </ul>
 *
 * <p>Listing only - the submission is downloaded and decrypted once, later, in
 * {@code LoadSubmissionDelegate} inside the per-submission child process.
 *
 * <p>No de-duplication guard: this is a scheduled process, poll cycles do not
 * overlap, and an accepted or rejected submission is deleted from the delivery
 * service so it is not offered again. A submission left on the service (routed
 * to manual review) is simply re-listed next cycle; its child process handles
 * that via the case lookup.
 */
@Named("pollSubmissionsDelegate")
@ApplicationScoped
@Slf4j
public class PollSubmissionsDelegate implements JavaDelegate {

    private SubscriberClientProvider clientProvider;
    private FitConnectCamundaConfig config;

    /** CDI. */
    protected PollSubmissionsDelegate() {
    }

    @Inject
    public PollSubmissionsDelegate(SubscriberClientProvider clientProvider, FitConnectCamundaConfig config) {
        this.clientProvider = clientProvider;
        this.config = config;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID destinationId = UUID.fromString(config.destinationId());
        int limit = config.pollLimit();

        List<SubmissionForPickup> available =
                clientProvider.getClient().getAvailableSubmissionsForDestination(destinationId, 0, limit);

        ArrayList<String> refs = new ArrayList<>(available.size());
        for (SubmissionForPickup submission : available) {
            refs.add(submission.getSubmissionId().toString());
        }

        execution.setVariable("submissionRefs", refs);
        execution.setVariable("submissionCount", refs.size());
        log.info("Destination {} has {} submission(s) to handle", destinationId, refs.size());
    }
}
