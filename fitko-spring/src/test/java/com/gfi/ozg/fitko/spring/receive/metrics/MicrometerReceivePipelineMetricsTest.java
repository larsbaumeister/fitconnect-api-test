package com.gfi.ozg.fitko.spring.receive.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerReceivePipelineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerReceivePipelineMetrics metrics = new MicrometerReceivePipelineMetrics(registry);

    private final UUID destinationA = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private final UUID destinationB = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    @Test
    void pollCompletedRecordsATimerAndTheSubmissionsFoundCounterTaggedByDestination() {
        metrics.pollCompleted(destinationA, Duration.ofMillis(120), 3);

        assertThat(registry.get("fitconnect.receive.poll")
                .tags("destination", destinationA.toString(), "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("fitconnect.receive.submissions.found")
                .tag("destination", destinationA.toString())
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void pollCompletedWithNothingWaitingStillRegistersTheFoundCounterAtZero() {
        metrics.pollCompleted(destinationA, Duration.ofMillis(5), 0);

        assertThat(registry.get("fitconnect.receive.submissions.found")
                .tag("destination", destinationA.toString())
                .counter().count()).isEqualTo(0.0);
    }

    @Test
    void pollFailedRecordsThePollTimerWithAFailureOutcome() {
        metrics.pollFailed(destinationA, Duration.ofMillis(90));

        assertThat(registry.get("fitconnect.receive.poll")
                .tags("destination", destinationA.toString(), "outcome", "failure")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void submissionProcessedAndFailedCountersAreSeparateAndPerDestination() {
        metrics.submissionProcessed(destinationA);
        metrics.submissionProcessed(destinationA);
        metrics.submissionFailed(destinationA);
        metrics.submissionProcessed(destinationB);

        assertThat(registry.get("fitconnect.receive.submissions.processed")
                .tag("destination", destinationA.toString()).counter().count()).isEqualTo(2.0);
        assertThat(registry.get("fitconnect.receive.submissions.failed")
                .tag("destination", destinationA.toString()).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fitconnect.receive.submissions.processed")
                .tag("destination", destinationB.toString()).counter().count()).isEqualTo(1.0);
    }
}
