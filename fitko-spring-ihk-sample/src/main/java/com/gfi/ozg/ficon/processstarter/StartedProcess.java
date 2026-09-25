package com.gfi.ozg.ficon.processstarter;

import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link ProcessStarter} actually started - stored on the submission
 * by {@code AntragDispatcher} ({@code process_definition}, {@code
 * process_instance_id}), so it is traceable which process runs for which
 * Antrag.
 *
 * @param processDefinition which process was started, e.g. a Camunda process
 *     definition key ({@code "ausbildungsvertrag-eintragung"}) - required
 * @param processInstanceId the id of the started instance in the target
 *     system, when it provides one (e.g. Camunda's process instance id) -
 *     what to look it up by there
 */
public record StartedProcess(String processDefinition, String processInstanceId) {

    public StartedProcess {
        Objects.requireNonNull(processDefinition, "processDefinition must not be null");
        if (processDefinition.isBlank()) {
            throw new IllegalArgumentException("processDefinition must not be blank");
        }
    }

    /** A started process whose target system hands out no instance id. */
    public static StartedProcess withoutInstanceId(String processDefinition) {
        return new StartedProcess(processDefinition, null);
    }

    public Optional<String> instanceId() {
        return Optional.ofNullable(processInstanceId);
    }
}
