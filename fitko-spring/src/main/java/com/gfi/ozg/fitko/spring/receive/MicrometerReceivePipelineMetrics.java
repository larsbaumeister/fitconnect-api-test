package com.gfi.ozg.fitko.spring.receive;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ReceivePipelineMetrics} backed by Micrometer. Records, tagged by
 * {@code destination} (the Zustellpunkt id):
 *
 * <ul>
 *   <li>{@value #POLL_TIMER} - timer, additionally tagged {@code outcome=success|failure};
 *       count + total time of poll cycles per destination.</li>
 *   <li>{@value #SUBMISSIONS_FOUND} - counter; submissions listed as available.</li>
 *   <li>{@value #SUBMISSIONS_PROCESSED} - counter; submissions downloaded and published without error.</li>
 *   <li>{@value #SUBMISSIONS_FAILED} - counter; submissions whose download/publish threw.</li>
 * </ul>
 *
 * <p>"Polling is healthy but idle" then reads as {@value #POLL_TIMER} with
 * {@code outcome=success} still incrementing while {@value #SUBMISSIONS_FOUND}
 * stays flat; "polling has been failing" as {@code outcome=failure} climbing.
 * Only instantiated by {@code FitConnectReceiveMetricsAutoConfiguration}, which
 * is {@code @ConditionalOnClass(MeterRegistry.class)}.
 */
public class MicrometerReceivePipelineMetrics implements ReceivePipelineMetrics {

    static final String POLL_TIMER = "fitconnect.receive.poll";
    static final String SUBMISSIONS_FOUND = "fitconnect.receive.submissions.found";
    static final String SUBMISSIONS_PROCESSED = "fitconnect.receive.submissions.processed";
    static final String SUBMISSIONS_FAILED = "fitconnect.receive.submissions.failed";

    private static final String DESTINATION_TAG = "destination";

    private final MeterRegistry registry;

    public MicrometerReceivePipelineMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void pollCompleted(UUID destinationId, Duration duration, int submissionsFound) {
        String destination = destinationId.toString();
        registry.timer(POLL_TIMER, DESTINATION_TAG, destination, "outcome", "success").record(duration);
        // increment(0) still registers the counter, so operators see it exists before the first submission ever arrives.
        registry.counter(SUBMISSIONS_FOUND, DESTINATION_TAG, destination).increment(submissionsFound);
    }

    @Override
    public void pollFailed(UUID destinationId, Duration duration) {
        registry.timer(POLL_TIMER, DESTINATION_TAG, destinationId.toString(), "outcome", "failure").record(duration);
    }

    @Override
    public void submissionProcessed(UUID destinationId) {
        registry.counter(SUBMISSIONS_PROCESSED, DESTINATION_TAG, destinationId.toString()).increment();
    }

    @Override
    public void submissionFailed(UUID destinationId) {
        registry.counter(SUBMISSIONS_FAILED, DESTINATION_TAG, destinationId.toString()).increment();
    }
}
