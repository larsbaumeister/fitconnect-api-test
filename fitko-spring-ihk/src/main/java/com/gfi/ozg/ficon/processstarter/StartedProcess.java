package com.gfi.ozg.ficon.processstarter;

import java.util.Objects;

/**
 * What a {@link ProcessStarter} started - stored on the submission ({@code
 * process_definition}, {@code process_instance_id}) to look up in the target
 * system which process runs for which Antrag.
 *
 * @param processDefinition which process was started, e.g. a Camunda process definition key - required
 * @param processInstanceId the started instance's id in the target system, or
 *                          {@code null} if that system hands out none
 */
public record StartedProcess(String processDefinition, String processInstanceId) {

    public StartedProcess {
        Objects.requireNonNull(processDefinition, "processDefinition must not be null");
        if (processDefinition.isBlank()) {
            throw new IllegalArgumentException("processDefinition must not be blank");
        }
    }

    public static StartedProcess withoutInstanceId(String processDefinition) {
        return new StartedProcess(processDefinition, null);
    }
}
