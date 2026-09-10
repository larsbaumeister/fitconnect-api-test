package com.gfi.ozg.fitko.camunda.process;

import com.gfi.ozg.fitko.camunda.config.FitConnectCamundaConfig;
import com.gfi.ozg.fitko.camunda.poll.PollScheduleConfig;
import com.gfi.ozg.fitko.camunda.routing.CaseLookupDelegate;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.junit5.ProcessEngineExtension;
import org.camunda.bpm.engine.test.mock.Mocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the {@code fitconnect-poll} dispatcher and the fully automated
 * {@code fitconnect-submission} child process on an in-memory engine with the
 * FIT-Connect delegates mocked. Checks the fixed order (persist -&gt; accept),
 * that every submission is accepted, and that {@code next-step-routing} then
 * forwards or archives it.
 */
class FitConnectPollProcessTest {

    private static final String BAUANTRAG = "urn:de:fim:leika:leistung:99000000000001";
    private static final String UNKNOWN = "urn:de:fim:leika:leistung:99999999999999";

    @RegisterExtension
    static final ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    private final AtomicInteger persisted = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger forwarded = new AtomicInteger();
    private final AtomicInteger archived = new AtomicInteger();

    @BeforeEach
    void registerCommonMocks() {
        FitConnectCamundaConfig config = new FitConnectCamundaConfig();
        config.set(FitConnectCamundaConfig.POLL_CYCLE, "R/PT5M");
        Mocks.register("pollScheduleConfig", new PollScheduleConfig(config));

        Mocks.register("persistSubmissionDelegate", (JavaDelegate) e -> {
            persisted.incrementAndGet();
            e.setVariable("persisted", true);
        });
        Mocks.register("acceptSubmissionDelegate", (JavaDelegate) e -> {
            accepted.incrementAndGet();
            e.setVariable("outcome", "ACCEPTED");
        });
        Mocks.register("forwardSubmissionDelegate", (JavaDelegate) e -> forwarded.incrementAndGet());
        Mocks.register("archiveSubmissionDelegate", (JavaDelegate) e -> archived.incrementAndGet());
    }

    @AfterEach
    void resetMocks() {
        Mocks.reset();
    }

    @Test
    @Deployment(resources = {"process/fitconnect-poll.bpmn", "process/fitconnect-submission.bpmn", "process/next-step-routing.dmn"})
    void acceptsEverySubmissionThenRoutesItByService() {
        Mocks.register("pollSubmissionsDelegate", (JavaDelegate) e -> {
            e.setVariable("submissionRefs", new ArrayList<>(List.of("submission-1", "submission-2")));
            e.setVariable("submissionCount", 2);
        });
        // submission-1 -> a known service (FORWARD); submission-2 -> unknown (ARCHIVE).
        Mocks.register("loadSubmissionDelegate", (JavaDelegate) e -> {
            String ref = (String) e.getVariable("submissionRef");
            e.setVariable("submissionId", ref);
            e.setVariable("caseId", "case-" + ref.substring(ref.length() - 1));
            e.setVariable("serviceIdentifier", ref.endsWith("1") ? BAUANTRAG : UNKNOWN);
        });
        Mocks.register("caseLookupDelegate", (JavaDelegate) e -> e.setVariable("caseKnown", false));

        RuntimeService runtimeService = engine.getProcessEngine().getRuntimeService();
        HistoryService historyService = engine.getProcessEngine().getHistoryService();

        runtimeService.startProcessInstanceByKey("fitconnect-poll");
        drainJobs();

        // Fixed order held and every submission was accepted.
        assertThat(persisted.get()).isEqualTo(2);
        assertThat(accepted.get()).isEqualTo(2);
        // ...then routed: one forwarded, one archived.
        assertThat(forwarded.get()).isEqualTo(1);
        assertThat(archived.get()).isEqualTo(1);

        // No child instance is left running; both are searchable by caseId in history.
        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey("fitconnect-submission")
                .variableValueEquals("caseId", "case-1")
                .finished().count()).isEqualTo(1);
        assertThat(historyService.createHistoricVariableInstanceQuery()
                .variableValueEquals("nextStepTarget", "bauantrag-fachverfahren").count()).isEqualTo(1);
    }

    @Test
    @Deployment(resources = {"process/fitconnect-poll.bpmn", "process/fitconnect-submission.bpmn", "process/next-step-routing.dmn"})
    void forwardsAFollowUpForAKnownCaseToTheClerkQueue() {
        Mocks.register("pollSubmissionsDelegate", (JavaDelegate) e -> {
            e.setVariable("submissionRefs", new ArrayList<>(List.of("submission-a", "submission-b")));
            e.setVariable("submissionCount", 2);
        });
        // Two submissions of the same (unknown) service and the same case.
        Mocks.register("loadSubmissionDelegate", (JavaDelegate) e -> {
            String ref = (String) e.getVariable("submissionRef");
            e.setVariable("submissionId", ref);
            e.setVariable("caseId", "shared-case");
            e.setVariable("serviceIdentifier", UNKNOWN);
        });
        // The real lookup: history query for other instances of the same case.
        Mocks.register("caseLookupDelegate", new CaseLookupDelegate());

        RuntimeService runtimeService = engine.getProcessEngine().getRuntimeService();
        HistoryService historyService = engine.getProcessEngine().getHistoryService();

        runtimeService.startProcessInstanceByKey("fitconnect-poll");
        drainJobs();

        assertThat(accepted.get()).isEqualTo(2);
        // First submission: new case -> ARCHIVE. Second: known case -> FORWARD to the clerk queue.
        assertThat(archived.get()).isEqualTo(1);
        assertThat(forwarded.get()).isEqualTo(1);
        assertThat(historyService.createHistoricVariableInstanceQuery()
                .variableValueEquals("nextStepTarget", "sachbearbeitung-followup").count()).isEqualTo(1);
    }

    @Test
    @Deployment(resources = {"process/fitconnect-poll.bpmn", "process/fitconnect-submission.bpmn", "process/next-step-routing.dmn"})
    void endsImmediatelyWhenNothingIsAvailable() {
        Mocks.register("pollSubmissionsDelegate", (JavaDelegate) e -> {
            e.setVariable("submissionRefs", new ArrayList<String>());
            e.setVariable("submissionCount", 0);
        });
        Mocks.register("loadSubmissionDelegate", (JavaDelegate) e -> { });
        Mocks.register("caseLookupDelegate", (JavaDelegate) e -> { });

        RuntimeService runtimeService = engine.getProcessEngine().getRuntimeService();
        runtimeService.startProcessInstanceByKey("fitconnect-poll");
        drainJobs();

        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
        assertThat(accepted.get()).isZero();
    }

    /** Runs every non-timer async job until none are left. */
    private void drainJobs() {
        ManagementService managementService = engine.getProcessEngine().getManagementService();
        for (int guard = 0; guard < 100; guard++) {
            List<Job> jobs = managementService.createJobQuery().list().stream()
                    .filter(job -> job.getProcessInstanceId() != null)
                    .toList();
            if (jobs.isEmpty()) {
                return;
            }
            jobs.forEach(job -> managementService.executeJob(job.getId()));
        }
        throw new IllegalStateException("jobs still pending after 100 rounds");
    }
}
