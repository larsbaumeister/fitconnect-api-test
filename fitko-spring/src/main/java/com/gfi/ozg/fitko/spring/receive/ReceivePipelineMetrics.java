package com.gfi.ozg.fitko.spring.receive;

import java.time.Duration;
import java.util.UUID;

/**
 * Callback surface the receive pipeline ({@link AntragPollingService},
 * {@link SubmissionProcessor}) invokes so operational metrics can be recorded
 * without either class depending on Micrometer directly - Micrometer is an
 * optional dependency of this starter, so the always-loaded receive classes
 * must not import it.
 *
 * <p>The Micrometer-backed implementation
 * ({@code MicrometerReceivePipelineMetrics}) is wired only by
 * {@code FitConnectReceiveMetricsAutoConfiguration}, which is
 * {@code @ConditionalOnClass(MeterRegistry.class)}. When Micrometer is absent
 * (or no {@code MeterRegistry} bean exists) the pipeline gets {@link #NOOP}
 * instead and records nothing.
 *
 * <p>All methods are called on the single {@code fitconnect-poller} thread for
 * polling, and on a request thread for the callback endpoint; implementations
 * must be safe for that and must never throw - a metrics failure must not
 * abort a poll cycle.
 */
public interface ReceivePipelineMetrics {

    /**
     * One destination was polled successfully (its available-submissions list
     * was retrieved). {@code duration} covers the list call plus inline
     * processing of every returned submission; {@code submissionsFound} is the
     * size of that list.
     */
    void pollCompleted(UUID destinationId, Duration duration, int submissionsFound);

    /** Polling one destination threw (network blip, expired token, ...); {@code duration} is time spent before the failure. */
    void pollFailed(UUID destinationId, Duration duration);

    /** One submission was downloaded and published without error (whether a listener resolved it or the default outcome applied). */
    void submissionProcessed(UUID destinationId);

    /** Downloading/publishing one submission threw; it stays on the delivery service for a later attempt. */
    void submissionFailed(UUID destinationId);

    /** No-op implementation used whenever Micrometer is not on the classpath or no {@code MeterRegistry} is available. */
    ReceivePipelineMetrics NOOP = new ReceivePipelineMetrics() {

        @Override
        public void pollCompleted(UUID destinationId, Duration duration, int submissionsFound) {
        }

        @Override
        public void pollFailed(UUID destinationId, Duration duration) {
        }

        @Override
        public void submissionProcessed(UUID destinationId) {
        }

        @Override
        public void submissionFailed(UUID destinationId) {
        }
    };
}
