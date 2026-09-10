package com.gfi.ozg.fitko.camunda.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * {@code nextStep == "ARCHIVE"} (the default): nothing further to do with this
 * submission - it has been persisted and accepted, and the process instance
 * (with its {@code caseId} / {@code submissionId} variables) is the record.
 *
 * <p>Placeholder: logs and finishes. A real implementation might set a
 * retention marker or push a "no action needed" note to the archive.
 */
@Named("archiveSubmissionDelegate")
@ApplicationScoped
@Slf4j
public class ArchiveSubmissionDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        log.info("Archiving submission {} (case {}, service {}) - no further processing",
                execution.getVariable("submissionId"), execution.getVariable("caseId"),
                execution.getVariable("serviceIdentifier"));
        execution.setVariable("nextStepOutcome", "ARCHIVED");
    }
}
