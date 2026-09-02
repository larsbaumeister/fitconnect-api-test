package com.gfi.ozg.fitko.spring.receive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link RetryCooldownStore} backed by a Spring {@link Cache}. The cache is
 * used purely as a shared, evictable key-value store; the "has the cooldown
 * elapsed?" decision is still an explicit {@code lastFailure + cooldown}
 * comparison here, so correctness never depends on the {@code CacheManager}
 * actually honouring a TTL (Spring's {@link Cache} API has no TTL concept,
 * and the in-process fallback has none).
 *
 * <p>Keys are {@code submissionId.toString()}; values are the last-failure
 * instant as an ISO-8601 {@link String} ({@link Instant#toString()}) so the
 * entry round-trips through any {@code RedisCacheManager} value serializer,
 * not just one configured for {@code java.time}. A value that fails to parse
 * (corrupt entry, a cache holding a different type) is treated as "not
 * cooling down" and evicted - a misconfigured consumer cache must never break
 * a poll cycle.
 */
@Slf4j
public class CacheRetryCooldownStore implements RetryCooldownStore {

    private final Cache cache;
    private final Duration cooldown;
    private final Clock clock;

    public CacheRetryCooldownStore(Cache cache, Duration cooldown) {
        this(cache, cooldown, Clock.systemUTC());
    }

    /**
     * A store backed by a self-pruning in-process cache, for use when the
     * application has no {@code CacheManager} of its own. Not shared across
     * replicas - see {@link ExpiringConcurrentMapCache}.
     */
    public static CacheRetryCooldownStore withInProcessFallback(String cacheName, Duration cooldown) {
        return new CacheRetryCooldownStore(
                new ExpiringConcurrentMapCache(cacheName, cooldown.multipliedBy(2)), cooldown);
    }

    CacheRetryCooldownStore(Cache cache, Duration cooldown, Clock clock) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean isCoolingDown(UUID submissionId) {
        String key = submissionId.toString();
        String recorded = cache.get(key, String.class);
        if (recorded == null) {
            return false;
        }
        Instant failedAt;
        try {
            failedAt = Instant.parse(recorded);
        } catch (DateTimeParseException e) {
            log.warn("Ignoring unparseable retry-cooldown entry for submission {} ({})", submissionId, recorded);
            cache.evict(key);
            return false;
        }
        Instant retryAt = failedAt.plus(cooldown);
        if (clock.instant().isBefore(retryAt)) {
            log.debug("Submission {} failed at {}, skipping until its {} retry-cooldown elapses at {}",
                    submissionId, failedAt, cooldown, retryAt);
            return true;
        }
        // Cooldown elapsed: drop the entry now instead of waiting for a later
        // success that may never come (the submission could be gone from the
        // delivery service by now).
        cache.evict(key);
        return false;
    }

    @Override
    public void recordOutcome(UUID submissionId, boolean succeeded) {
        String key = submissionId.toString();
        if (succeeded) {
            cache.evict(key);
        } else {
            cache.put(key, clock.instant().toString());
        }
    }
}
