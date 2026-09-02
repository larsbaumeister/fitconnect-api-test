package com.gfi.ozg.fitko.spring.receive;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiringConcurrentMapCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-02T10:00:00Z"));
    private final ExpiringConcurrentMapCache cache =
            new ExpiringConcurrentMapCache("test", Duration.ofMinutes(10), clock);

    @Test
    void keepsEntriesYoungerThanTheMaxAge() {
        cache.put("k", "v");
        clock.advance(Duration.ofMinutes(9));

        assertThat(cache.get("k", String.class)).isEqualTo("v");
    }

    @Test
    void dropsEntriesOlderThanTheMaxAgeOnTheNextAccess() {
        cache.put("k", "v");
        clock.advance(Duration.ofMinutes(11));

        assertThat(cache.get("k")).isNull();
    }

    @Test
    void pruningOnPutClearsOtherStaleEntries() {
        cache.put("old", "v1");
        clock.advance(Duration.ofMinutes(11));
        cache.put("new", "v2");

        assertThat(cache.getNativeCache()).containsOnlyKeys("new");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
