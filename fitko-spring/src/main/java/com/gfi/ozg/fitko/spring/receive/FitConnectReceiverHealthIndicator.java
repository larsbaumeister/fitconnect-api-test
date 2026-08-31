package com.gfi.ozg.fitko.spring.receive;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reports the {@link SubmissionPollingService}'s liveness as a Spring Boot Actuator
 * health contributor ({@code fitConnectReceiver} in {@code /actuator/health}).
 *
 * <p>The point is to distinguish "polling is healthy but idle" (no submissions
 * arriving, which is normal) from "polling has been failing for a while" - the
 * latter is otherwise invisible because {@link SubmissionPollingService} only logs
 * per-destination failures at {@code WARN} and keeps going.
 *
 * <ul>
 *   <li><b>UNKNOWN</b> - {@code polling.enabled=false}: nothing is being polled,
 *       so there is nothing to assert.</li>
 *   <li><b>UP</b> - every destination was polled successfully within the
 *       staleness window (or the startup grace period is still running).</li>
 *   <li><b>DOWN</b> - at least one destination has had no successful poll for
 *       longer than the staleness window. The {@code details} name each
 *       destination and its last-success timestamp (or {@code never}).</li>
 * </ul>
 *
 * <p>The staleness window is {@code initial-delay + 3 x interval}, and DOWN is
 * suppressed until that same span has elapsed since the poller started, so a
 * slow first cycle after boot doesn't flap the endpoint.
 */
public class FitConnectReceiverHealthIndicator implements HealthIndicator {

    private static final long STALE_INTERVALS = 3;

    private final SubmissionPollingService pollingService;

    public FitConnectReceiverHealthIndicator(SubmissionPollingService pollingService) {
        this.pollingService = Objects.requireNonNull(pollingService, "pollingService must not be null");
    }

    @Override
    public Health health() {
        if (!pollingService.isAutoStartup()) {
            return Health.unknown().withDetail("polling", "disabled").build();
        }

        Instant now = Instant.now();
        Duration staleWindow = pollingService.initialDelay()
                .plus(pollingService.pollInterval().multipliedBy(STALE_INTERVALS));
        Instant startedAt = pollingService.startedAt();
        boolean withinStartupGrace = startedAt == null
                || Duration.between(startedAt, now).compareTo(staleWindow) < 0;

        Map<UUID, Instant> lastSuccess = pollingService.lastSuccessfulPollByDestination();
        Health.Builder health = new Health.Builder()
                .withDetail("polling", pollingService.isRunning() ? "running" : "stopped")
                .withDetail("staleAfter", staleWindow.toString());

        boolean anyStale = false;
        for (UUID destinationId : pollingService.destinationIds()) {
            Instant last = lastSuccess.get(destinationId);
            if (last == null) {
                health.withDetail(destinationId.toString(), withinStartupGrace ? "no successful poll yet" : "never");
                anyStale |= !withinStartupGrace;
            } else {
                health.withDetail(destinationId.toString(), last.toString());
                anyStale |= Duration.between(last, now).compareTo(staleWindow) > 0;
            }
        }

        return (anyStale ? health.down() : health.up()).build();
    }
}
