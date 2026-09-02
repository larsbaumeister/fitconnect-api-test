package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.ReceivePipelineMetrics;
import com.gfi.ozg.fitko.spring.receive.RedisReceivePipelineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Opt-in fleet-wide receive metrics. When {@code
 * fitconnect.receiver.shared-metrics.enabled=true}, publishes an extra
 * {@link ReceivePipelineMetrics} that keeps the receive counts in Redis
 * (shared by every replica) and re-publishes them as {@code
 * fitconnect.receive.fleet.*} gauges - see {@link RedisReceivePipelineMetrics}.
 * It sits <em>alongside</em> the per-instance Micrometer meters from
 * {@link FitConnectReceiveMetricsAutoConfiguration};
 * {@link FitConnectReceiverAutoConfiguration} fans the pipeline out to both.
 *
 * <p>{@code @ConditionalOnClass} on both Micrometer and Spring Data Redis:
 * a consumer that opts in must have {@code spring-boot-starter-data-redis}
 * (with a configured {@code spring.data.redis.*}) and Micrometer on the
 * classpath. If the property is set but no {@code StringRedisTemplate} bean
 * exists this logs a warning and contributes nothing.
 */
@AutoConfiguration(after = FitConnectReceiveMetricsAutoConfiguration.class,
        before = FitConnectReceiverAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class, StringRedisTemplate.class})
@ConditionalOnBean(ApplicationConfig.class)
@ConditionalOnProperty(prefix = "fitconnect.receiver.shared-metrics", name = "enabled", havingValue = "true")
@Slf4j
public class FitConnectReceiveSharedMetricsAutoConfiguration {

    @Bean
    public ReceivePipelineMetrics fitConnectRedisReceivePipelineMetrics(ObjectProvider<StringRedisTemplate> redisTemplate,
                                                                        ObjectProvider<MeterRegistry> meterRegistry,
                                                                        FitConnectProperties properties) {
        StringRedisTemplate redis = redisTemplate.getIfAvailable();
        if (redis == null) {
            log.warn("fitconnect.receiver.shared-metrics.enabled=true but no StringRedisTemplate bean is present - "
                    + "add spring-boot-starter-data-redis and configure spring.data.redis.*; fleet metrics are off");
            return ReceivePipelineMetrics.NOOP;
        }

        FitConnectProperties.Receiver.SharedMetrics config = properties.getReceiver().getSharedMetrics();
        RedisReceivePipelineMetrics fleetMetrics = new RedisReceivePipelineMetrics(redis, config.getKeyPrefix());

        List<UUID> destinationIds = properties.getReceiver().getDestinations().stream()
                .map(FitConnectProperties.Receiver.Destination::getId)
                .toList();
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            fleetMetrics.registerFleetGauges(registry, destinationIds);
        } else {
            log.warn("fitconnect.receiver.shared-metrics.enabled=true but no MeterRegistry bean is present - "
                    + "counts are still shared in Redis, but the fitconnect.receive.fleet.* gauges are not exposed");
        }
        return fleetMetrics;
    }
}
