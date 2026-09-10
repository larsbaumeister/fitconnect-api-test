package com.gfi.ozg.fitko.camunda.poll;

import com.gfi.ozg.fitko.camunda.config.FitConnectCamundaConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Exposes the poll interval to the BPMN timer start event.
 *
 * <p>The {@code fitconnect-poll} process' timer start event uses
 * {@code <timeCycle>${pollScheduleConfig.pollCycle}</timeCycle>}. On WildFly
 * the Camunda subsystem resolves that expression against this deployment's CDI
 * bean manager, so the interval is driven by the {@code fitconnect.poll.cycle}
 * configuration instead of being hard-coded in the diagram.
 */
@Named("pollScheduleConfig")
@ApplicationScoped
public class PollScheduleConfig {

    private FitConnectCamundaConfig config;

    /** CDI. */
    protected PollScheduleConfig() {
    }

    @Inject
    public PollScheduleConfig(FitConnectCamundaConfig config) {
        this.config = config;
    }

    /** ISO-8601 repeating interval or cron expression, e.g. {@code R/PT5M}. */
    public String getPollCycle() {
        return config.pollCycle();
    }
}
