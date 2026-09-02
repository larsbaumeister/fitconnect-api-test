package com.gfi.ozg.fitko.spring.receive;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ReceivePipelineMetrics} that keeps its counts in Redis, shared by
 * every replica of the application, and re-publishes them as {@code
 * fitconnect.receive.fleet.*} gauges on the local {@link MeterRegistry}.
 *
 * <p>Each replica atomically {@code INCR}s the shared keys as it polls and
 * processes submissions, so a scrape of <em>any single</em> replica reports
 * the whole fleet's totals. Because every replica reports the same value,
 * aggregate the {@code fleet.*} gauges with {@code max} (or scrape one
 * instance) - never {@code sum}. The per-instance {@code fitconnect.receive.*}
 * meters are unaffected and still there for per-replica views.
 *
 * <p>Only instantiated by {@code FitConnectReceiveSharedMetricsAutoConfiguration}
 * ({@code @ConditionalOnClass} on both Micrometer and Spring Data Redis, and
 * {@code fitconnect.receiver.shared-metrics.enabled=true}). Every Redis call
 * is best-effort: a Redis outage is logged at debug and the poll cycle
 * carries on; the gauges then report {@code NaN} until Redis is back.
 */
@Slf4j
public class RedisReceivePipelineMetrics implements ReceivePipelineMetrics {

    private static final String FOUND = "fitconnect.receive.fleet.submissions.found";
    private static final String PROCESSED = "fitconnect.receive.fleet.submissions.processed";
    private static final String FAILED = "fitconnect.receive.fleet.submissions.failed";
    private static final String POLL_COUNT = "fitconnect.receive.fleet.poll.count";

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisReceivePipelineMetrics(StringRedisTemplate redis, String keyPrefix) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
    }

    @Override
    public void pollCompleted(UUID destinationId, Duration duration, int submissionsFound) {
        incrementBy(pollCountKey(destinationId, "success"), 1);
        if (submissionsFound > 0) {
            incrementBy(submissionsKey("found", destinationId), submissionsFound);
        }
    }

    @Override
    public void pollFailed(UUID destinationId, Duration duration) {
        incrementBy(pollCountKey(destinationId, "failure"), 1);
    }

    @Override
    public void submissionProcessed(UUID destinationId) {
        incrementBy(submissionsKey("processed", destinationId), 1);
    }

    @Override
    public void submissionFailed(UUID destinationId) {
        incrementBy(submissionsKey("failed", destinationId), 1);
    }

    /**
     * Registers the read-through {@code fitconnect.receive.fleet.*} gauges for
     * each configured destination. Call once, at startup.
     */
    public void registerFleetGauges(MeterRegistry registry, Collection<UUID> destinationIds) {
        for (UUID destinationId : destinationIds) {
            String destination = destinationId.toString();
            gauge(registry, FOUND, destination, submissionsKey("found", destinationId));
            gauge(registry, PROCESSED, destination, submissionsKey("processed", destinationId));
            gauge(registry, FAILED, destination, submissionsKey("failed", destinationId));
            for (String outcome : new String[] {"success", "failure"}) {
                Gauge.builder(POLL_COUNT, this, m -> m.read(pollCountKey(destinationId, outcome)))
                        .tag("destination", destination)
                        .tag("outcome", outcome)
                        .register(registry);
            }
        }
    }

    private void gauge(MeterRegistry registry, String name, String destination, String key) {
        Gauge.builder(name, this, m -> m.read(key))
                .tag("destination", destination)
                .register(registry);
    }

    private void incrementBy(String key, long delta) {
        try {
            redis.opsForValue().increment(key, delta);
        } catch (RuntimeException e) {
            log.debug("Could not increment shared receive metric {} (+{})", key, delta, e);
        }
    }

    private double read(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0d : Double.parseDouble(value);
        } catch (RuntimeException e) {
            log.debug("Could not read shared receive metric {}", key, e);
            return Double.NaN;
        }
    }

    private String submissionsKey(String kind, UUID destinationId) {
        return keyPrefix + "submissions:" + kind + ":" + destinationId;
    }

    private String pollCountKey(UUID destinationId, String outcome) {
        return keyPrefix + "poll:count:" + destinationId + ":" + outcome;
    }
}
