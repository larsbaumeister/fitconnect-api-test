package com.gfi.ozg.fitko.spring.receive;

import com.gfi.ozg.fitko.spring.FitConnectProperties;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PollCycleGate} that holds a ShedLock lock for the duration of each
 * poll cycle, so that when the application runs as several replicas all
 * polling the same FIT-Connect destination(s), only one replica runs any
 * given cycle instead of every replica re-downloading and re-publishing every
 * unresolved submission. Listeners must be idempotent regardless - this is a
 * cost/efficiency measure, not a correctness fix.
 *
 * <p>One lock per poll cycle (not per destination), named from a hash of the
 * configured destination-id set so two applications polling <em>different</em>
 * destinations do not block each other. If the lock is already held the cycle
 * is skipped quietly and retried on the next {@code polling.interval}.
 *
 * <p>Wired only by {@code FitConnectPollLockAutoConfiguration}, which is
 * {@code @ConditionalOnClass(LockProvider.class)} - so {@code
 * net.javacrumbs.shedlock} is never loaded unless the consumer put it on the
 * classpath and declared a {@code LockProvider} bean.
 */
@Slf4j
public class ShedLockPollCycleGate implements PollCycleGate {

    private final LockProvider lockProvider;
    private final String lockName;
    private final Duration lockAtMostFor;
    private final Duration lockAtLeastFor;

    public ShedLockPollCycleGate(LockProvider lockProvider, List<UUID> destinationIds,
                                 FitConnectProperties.Polling.DistributedLock config, Duration interval) {
        this.lockProvider = Objects.requireNonNull(lockProvider, "lockProvider must not be null");
        this.lockName = "fitconnect-poll-" + shortHash(destinationIds);
        this.lockAtMostFor = config.getLockAtMostFor() != null
                ? config.getLockAtMostFor() : interval.multipliedBy(10);
        this.lockAtLeastFor = config.getLockAtLeastFor() != null
                ? config.getLockAtLeastFor() : interval;
        log.info("FIT-Connect poll cycles are coordinated across replicas via ShedLock "
                + "(lock '{}', at-most-for {}, at-least-for {})", lockName, lockAtMostFor, lockAtLeastFor);
    }

    @Override
    public void runPollCycle(Runnable pollCycle) {
        LockConfiguration lockConfiguration =
                new LockConfiguration(Instant.now(), lockName, lockAtMostFor, lockAtLeastFor);
        Optional<SimpleLock> lock = lockProvider.lock(lockConfiguration);
        if (lock.isEmpty()) {
            log.debug("FIT-Connect poll cycle skipped: lock '{}' held by another replica", lockName);
            return;
        }
        try {
            pollCycle.run();
        } finally {
            lock.get().unlock();
        }
    }

    /** First 16 hex chars of the SHA-256 of the sorted, comma-joined destination ids. */
    private static String shortHash(List<UUID> destinationIds) {
        String canonical = destinationIds.stream()
                .map(UUID::toString)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
