package com.gfi.ozg.fitko.spring.receive.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CompositeReceivePipelineMetricsTest {

    private static final UUID DESTINATION = UUID.randomUUID();

    @Test
    void fansEveryCallbackOutToEveryDelegate() {
        ReceivePipelineMetrics a = mock(ReceivePipelineMetrics.class);
        ReceivePipelineMetrics b = mock(ReceivePipelineMetrics.class);
        CompositeReceivePipelineMetrics composite = new CompositeReceivePipelineMetrics(List.of(a, b));

        composite.pollCompleted(DESTINATION, Duration.ofSeconds(1), 2);
        composite.pollFailed(DESTINATION, Duration.ZERO);
        composite.submissionProcessed(DESTINATION);
        composite.submissionFailed(DESTINATION);

        for (ReceivePipelineMetrics delegate : List.of(a, b)) {
            verify(delegate).pollCompleted(DESTINATION, Duration.ofSeconds(1), 2);
            verify(delegate).pollFailed(DESTINATION, Duration.ZERO);
            verify(delegate).submissionProcessed(DESTINATION);
            verify(delegate).submissionFailed(DESTINATION);
        }
    }

    @Test
    void oneThrowingDelegateDoesNotStopTheOthersOrPropagate() {
        ReceivePipelineMetrics bad = mock(ReceivePipelineMetrics.class);
        ReceivePipelineMetrics good = mock(ReceivePipelineMetrics.class);
        doThrow(new RuntimeException("boom")).when(bad).submissionFailed(any());
        CompositeReceivePipelineMetrics composite = new CompositeReceivePipelineMetrics(List.of(bad, good));

        assertThatNoException().isThrownBy(() -> composite.submissionFailed(DESTINATION));
        verify(good).submissionFailed(eq(DESTINATION));
    }
}
