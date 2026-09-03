package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import com.gfi.ozg.fitko.spring.receive.metrics.MicrometerReceivePipelineMetrics;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires the Micrometer-backed {@link ReceivePipelineMetrics} the receive
 * pipeline reports poll timings and submission counts to (see
 * {@link MicrometerReceivePipelineMetrics} for the meter list).
 *
 * <p>{@code @ConditionalOnClass(MeterRegistry.class)}: a consumer that doesn't
 * already run Micrometer never pulls the optional {@code micrometer-core} jar,
 * this class isn't loaded, and {@link FitConnectReceiverAutoConfiguration}
 * falls back to {@link ReceivePipelineMetrics#NOOP}. The same
 * {@code @ConditionalOnBean(ApplicationConfig.class)} + {@code fitconnect.receiver.enabled}
 * gate as the receiver config itself keeps this from contributing a stray bean
 * when the starter (or just the receiver side) is switched off. Ordered
 * {@code before} the receiver config so the bean is present when the pipeline
 * beans are built; if Micrometer is on the classpath but no {@link MeterRegistry}
 * bean exists, {@code NOOP} is used too.
 */
@AutoConfiguration(after = FitConnectAutoConfiguration.class, before = FitConnectReceiverAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(ApplicationConfig.class)
@ConditionalOnProperty(prefix = "fitconnect.receiver", name = "enabled", matchIfMissing = true)
public class FitConnectReceiveMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReceivePipelineMetrics fitConnectReceivePipelineMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        return registry != null ? new MicrometerReceivePipelineMetrics(registry) : ReceivePipelineMetrics.NOOP;
    }
}
