package com.gfi.ozg.fitko.camunda.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.history.HistoricProcessInstance;

import java.util.List;

/**
 * Decides whether this submission belongs to a case we have already seen.
 *
 * <p>Queries Camunda's history for other {@code fitconnect-submission} process
 * instances (running or finished) that carry the same {@code caseId} variable.
 * A FIT-Connect {@code caseId} groups a submission with any later
 * submissions/replies for the same matter, so "another instance exists" means
 * this is a follow-up rather than a brand-new case.
 *
 * <p>Sets:
 * <ul>
 *   <li>{@code caseKnown} - {@code boolean}, a {@code submission-routing} input;</li>
 *   <li>{@code priorCaseInstanceCount} - how many other instances were found.</li>
 * </ul>
 *
 * <p>Uses the Camunda history as the "have we handled this case before" record.
 * If the Fachverfahren is the real source of truth, replace the query below
 * with a lookup there. Needs history level {@code full} (the default on the
 * shared engine) for the {@code caseId} variable to be queryable.
 */
@Named("caseLookupDelegate")
@ApplicationScoped
@Slf4j
public class CaseLookupDelegate implements JavaDelegate {

    static final String CHILD_PROCESS_KEY = "fitconnect-submission";

    @Override
    public void execute(DelegateExecution execution) {
        String caseId = (String) execution.getVariable("caseId");
        HistoryService historyService = execution.getProcessEngineServices().getHistoryService();

        List<HistoricProcessInstance> sameCase = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(CHILD_PROCESS_KEY)
                .variableValueEquals("caseId", caseId)
                .list();

        long others = sameCase.stream()
                .filter(instance -> !instance.getId().equals(execution.getProcessInstanceId()))
                .count();

        boolean caseKnown = others > 0;
        execution.setVariable("caseKnown", caseKnown);
        execution.setVariable("priorCaseInstanceCount", (int) others);
        log.info("Case {} for submission {}: caseKnown={} ({} prior instance(s))",
                caseId, execution.getVariable("submissionId"), caseKnown, others);
    }
}
