package com.gfi.ozg.fitko.spring.receive.metrics;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Fans every {@link ReceivePipelineMetrics} callback out to several
 * delegates, so the receive pipeline can report to the local Micrometer
 * meters and any extra {@link ReceivePipelineMetrics} a consumer contributes
 * at the same time. One delegate throwing does not stop the others - the
 * pipeline must never be aborted by a metrics failure.
 */
@Slf4j
public class CompositeReceivePipelineMetrics implements ReceivePipelineMetrics {

    private final List<ReceivePipelineMetrics> delegates;

    public CompositeReceivePipelineMetrics(List<ReceivePipelineMetrics> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void pollCompleted(UUID destinationId, Duration duration, int submissionsFound) {
        forEach(m -> m.pollCompleted(destinationId, duration, submissionsFound));
    }

    @Override
    public void pollFailed(UUID destinationId, Duration duration) {
        forEach(m -> m.pollFailed(destinationId, duration));
    }

    @Override
    public void submissionProcessed(UUID destinationId) {
        forEach(m -> m.submissionProcessed(destinationId));
    }

    @Override
    public void submissionFailed(UUID destinationId) {
        forEach(m -> m.submissionFailed(destinationId));
    }

    private void forEach(java.util.function.Consumer<ReceivePipelineMetrics> call) {
        for (ReceivePipelineMetrics delegate : delegates) {
            try {
                call.accept(delegate);
            } catch (RuntimeException e) {
                log.debug("Receive-pipeline metrics delegate {} failed", delegate.getClass().getSimpleName(), e);
            }
        }
    }
}
