package com.gfi.ozg.fitko.spring.receive.cooldown;

import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ConcurrentMapCache} that forgets entries older than a fixed
 * maximum age. Used only as the in-process fallback for {@link
 * CacheRetryCooldownStore} when the application has no {@code CacheManager}
 * of its own - it keeps a long-lived JVM from accumulating entries for
 * submissions that failed once and were never seen again. It is <em>not</em>
 * a substitute for a real shared cache in a multi-replica deployment; there
 * the consumer should configure a Redis (or similar) cache named {@link
 * RetryCooldownStore#CACHE_NAME}.
 *
 * <p>Pruning is lazy: every {@link #lookup}/{@link #put} first drops any
 * entry whose insertion is older than {@code maxAge}. No background thread.
 */
class ExpiringConcurrentMapCache extends ConcurrentMapCache {

    private final Duration maxAge;
    private final Clock clock;
    private final Map<Object, Instant> insertedAt = new ConcurrentHashMap<>();

    ExpiringConcurrentMapCache(String name, Duration maxAge) {
        this(name, maxAge, Clock.systemUTC());
    }

    ExpiringConcurrentMapCache(String name, Duration maxAge, Clock clock) {
        super(name);
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Override
    protected Object lookup(Object key) {
        prune();
        return super.lookup(key);
    }

    @Override
    public void put(Object key, Object value) {
        prune();
        insertedAt.put(key, clock.instant());
        super.put(key, value);
    }

    @Override
    public void evict(Object key) {
        insertedAt.remove(key);
        super.evict(key);
    }

    @Override
    public void clear() {
        insertedAt.clear();
        super.clear();
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(maxAge);
        insertedAt.forEach((key, stamp) -> {
            if (stamp.isBefore(cutoff)) {
                insertedAt.remove(key);
                super.evict(key);
            }
        });
    }
}
