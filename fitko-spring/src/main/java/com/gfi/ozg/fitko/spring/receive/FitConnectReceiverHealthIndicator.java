package com.gfi.ozg.fitko.spring.receive;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/**
 * Reports whether <em>this</em> application instance's {@link
 * SubmissionPollingService} is up and running, as the {@code fitConnectReceiver}
 * entry in {@code /actuator/health}.
 *
 * <p>It is a plain liveness signal for the poller thread in this JVM. It does
 * <em>not</em> try to infer the health of FIT-Connect, of other replicas, or
 * whether submissions are actually flowing - that is what the
 * {@code fitconnect.receive.*} metrics are for.
 *
 * <ul>
 *   <li><b>UP</b> - the poller is running, or polling is turned off
 *       ({@code fitconnect.receiver.polling.enabled=false}, e.g. callback-only
 *       mode) so there is deliberately nothing for it to run.</li>
 *   <li><b>DOWN</b> - polling is enabled but the poller is not running: it
 *       failed to start, or has since stopped.</li>
 * </ul>
 */
public class FitConnectReceiverHealthIndicator implements HealthIndicator {

    private final SubmissionPollingService pollingService;

    public FitConnectReceiverHealthIndicator(SubmissionPollingService pollingService) {
        this.pollingService = Objects.requireNonNull(pollingService, "pollingService must not be null");
    }

    @Override
    public Health health() {
        if (!pollingService.isAutoStartup()) {
            return Health.up().withDetail("polling", "disabled").build();
        }
        return pollingService.isRunning()
                ? Health.up().withDetail("polling", "running").build()
                : Health.down().withDetail("polling", "stopped").build();
    }
}
