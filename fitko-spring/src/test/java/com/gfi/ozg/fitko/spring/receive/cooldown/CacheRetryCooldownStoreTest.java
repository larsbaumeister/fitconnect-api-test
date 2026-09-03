package com.gfi.ozg.fitko.spring.receive.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests the {@link CacheRetryCooldownStore} cooldown arithmetic and its
 * eviction behaviour directly against a plain {@link ConcurrentMapCache} and a
 * hand-advanced clock - no Spring context, no waiting on real time.
 */
class CacheRetryCooldownStoreTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(20);
    private static final UUID SUBMISSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MutableClock clock;
    private Cache cache;
    private CacheRetryCooldownStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-02T10:00:00Z"));
        cache = new ConcurrentMapCache(RetryCooldownStore.CACHE_NAME);
        store = new CacheRetryCooldownStore(cache, COOLDOWN, clock);
    }

    @Test
    void nothingRecordedMeansNotCoolingDown() {
        assertThat(store.isCoolingDown(SUBMISSION_ID)).isFalse();
    }

    @Test
    void aRecordedFailureCoolsDownForTheDurationOfTheCooldown() {
        store.recordOutcome(SUBMISSION_ID, false);

        assertThat(store.isCoolingDown(SUBMISSION_ID)).isTrue();

        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(store.isCoolingDown(SUBMISSION_ID)).isTrue();
    }

    @Test
    void onceTheCooldownElapsesItStopsCoolingDownAndTheEntryIsEvicted() {
        store.recordOutcome(SUBMISSION_ID, false);
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThat(store.isCoolingDown(SUBMISSION_ID)).isFalse();
        assertThat(cache.get(SUBMISSION_ID.toString())).isNull();
    }

    @Test
    void aSuccessfulOutcomeClearsTheCooldownImmediately() {
        store.recordOutcome(SUBMISSION_ID, false);
        assertThat(store.isCoolingDown(SUBMISSION_ID)).isTrue();

        store.recordOutcome(SUBMISSION_ID, true);

        assertThat(store.isCoolingDown(SUBMISSION_ID)).isFalse();
        assertThat(cache.get(SUBMISSION_ID.toString())).isNull();
    }

    @Test
    void aRepeatFailureRestartsTheCooldownFromNow() {
        store.recordOutcome(SUBMISSION_ID, false);
        clock.advance(COOLDOWN.minusMinutes(1));

        store.recordOutcome(SUBMISSION_ID, false);
        clock.advance(Duration.ofMinutes(2)); // past the first window, not the second

        assertThat(store.isCoolingDown(SUBMISSION_ID)).isTrue();
    }

    @Test
    void anUnparseableEntryIsTreatedAsNotCoolingDownAndEvicted() {
        cache.put(SUBMISSION_ID.toString(), "not-a-timestamp");

        assertThat(store.isCoolingDown(SUBMISSION_ID)).isFalse();
        assertThat(cache.get(SUBMISSION_ID.toString())).isNull();
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
