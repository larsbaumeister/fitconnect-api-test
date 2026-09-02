package com.gfi.ozg.fitko.spring.receive;

import java.util.UUID;

/**
 * Records which submissions failed processing recently, so {@link
 * SubmissionPollingService} can honour {@code
 * fitconnect.receiver.polling.retry-cooldown} - not re-fetching a submission
 * that just failed on every single poll cycle.
 *
 * <p>The state is one entry per <em>currently</em> failing submission (id -&gt;
 * last-failure timestamp). The default implementation ({@link
 * CacheRetryCooldownStore}) keeps it in a Spring {@link
 * org.springframework.cache.Cache}: back that cache with Redis (or any other
 * shared {@code CacheManager}) and the cooldown is shared across replicas;
 * with no {@code CacheManager} a self-pruning in-process cache is used
 * instead. Entries are removed on success, and once the cooldown has elapsed -
 * so a submission that fails once and then leaves the delivery service
 * without ever succeeding here does not leak an entry forever.
 *
 * <p>Every method is called only on the single {@code fitconnect-poller}
 * thread and must never throw - a bookkeeping failure must not abort a poll
 * cycle.
 */
public interface RetryCooldownStore {

    /** Name of the Spring {@link org.springframework.cache.Cache} the default implementation uses. */
    String CACHE_NAME = "fitconnect-retry-cooldown";

    /**
     * {@code true} if {@code submissionId} failed within the last {@code
     * retry-cooldown} and should be skipped this cycle. Implementations may
     * evict an entry whose cooldown has elapsed as a side effect of this
     * check.
     */
    boolean isCoolingDown(UUID submissionId);

    /**
     * Feeds the outcome of a processing attempt back in: {@code succeeded ==
     * true} clears any cooldown for {@code submissionId}, {@code false}
     * (re)starts it from now.
     */
    void recordOutcome(UUID submissionId, boolean succeeded);

    /**
     * No-op store used when {@code retry-cooldown} is unset - the poller then
     * behaves exactly as if this feature did not exist.
     */
    RetryCooldownStore NONE = new RetryCooldownStore() {

        @Override
        public boolean isCoolingDown(UUID submissionId) {
            return false;
        }

        @Override
        public void recordOutcome(UUID submissionId, boolean succeeded) {
        }
    };
}
