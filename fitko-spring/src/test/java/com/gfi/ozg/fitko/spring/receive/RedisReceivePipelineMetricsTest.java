package com.gfi.ozg.fitko.spring.receive;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisReceivePipelineMetricsTest {

    private static final UUID DESTINATION = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final String PREFIX = "fitconnect:receive:";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    private RedisReceivePipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        metrics = new RedisReceivePipelineMetrics(redis, PREFIX);
    }

    @Test
    void incrementsTheSharedKeysOnEachPipelineEvent() {
        metrics.pollCompleted(DESTINATION, Duration.ofSeconds(1), 3);
        metrics.pollFailed(DESTINATION, Duration.ZERO);
        metrics.submissionProcessed(DESTINATION);
        metrics.submissionFailed(DESTINATION);

        verify(ops).increment(PREFIX + "poll:count:" + DESTINATION + ":success", 1L);
        verify(ops).increment(PREFIX + "submissions:found:" + DESTINATION, 3L);
        verify(ops).increment(PREFIX + "poll:count:" + DESTINATION + ":failure", 1L);
        verify(ops).increment(PREFIX + "submissions:processed:" + DESTINATION, 1L);
        verify(ops).increment(PREFIX + "submissions:failed:" + DESTINATION, 1L);
    }

    @Test
    void doesNotTouchTheFoundKeyWhenAPollReturnedNothing() {
        metrics.pollCompleted(DESTINATION, Duration.ofSeconds(1), 0);

        verify(ops, never()).increment(contains("submissions:found"), anyLong());
    }

    @Test
    void fleetGaugesReadTheCurrentRedisValues() {
        when(ops.get(PREFIX + "submissions:processed:" + DESTINATION)).thenReturn("42");
        when(ops.get(PREFIX + "submissions:found:" + DESTINATION)).thenReturn(null);

        metrics.registerFleetGauges(registry, List.of(DESTINATION));

        assertThat(gauge("fitconnect.receive.fleet.submissions.processed")).isEqualTo(42d);
        assertThat(gauge("fitconnect.receive.fleet.submissions.found")).isEqualTo(0d);
    }

    @Test
    void aRedisOutageNeverPropagatesOutOfAPipelineEvent() {
        when(ops.increment(anyString(), anyLong())).thenThrow(new RuntimeException("redis down"));

        assertThatNoException().isThrownBy(() -> metrics.submissionProcessed(DESTINATION));
    }

    @Test
    void aRedisOutageMakesTheFleetGaugesReportNaN() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        metrics.registerFleetGauges(registry, List.of(DESTINATION));

        assertThat(gauge("fitconnect.receive.fleet.submissions.failed")).isNaN();
    }

    private double gauge(String name) {
        return registry.get(name).tag("destination", DESTINATION.toString()).gauge().value();
    }
}
