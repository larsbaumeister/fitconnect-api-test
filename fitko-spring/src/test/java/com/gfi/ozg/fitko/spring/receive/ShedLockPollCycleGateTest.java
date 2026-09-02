package com.gfi.ozg.fitko.spring.receive;

import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.support.InMemoryLockProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShedLockPollCycleGateTest {

    private static final List<UUID> DESTINATIONS = List.of(
            UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7"),
            UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234"));

    private final InMemoryLockProvider lockProvider = new InMemoryLockProvider();

    private ShedLockPollCycleGate gate(List<UUID> destinationIds) {
        return new ShedLockPollCycleGate(lockProvider, destinationIds,
                new FitConnectProperties.Polling.DistributedLock(), Duration.ofSeconds(30));
    }

    @Test
    void runsThePollCycleWhenTheLockIsFree() {
        AtomicInteger runs = new AtomicInteger();

        gate(DESTINATIONS).runPollCycle(runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void releasesTheLockAfterEachCycle() {
        ShedLockPollCycleGate gate = gate(DESTINATIONS);

        gate.runPollCycle(() -> { });
        AtomicInteger secondRun = new AtomicInteger();
        gate.runPollCycle(secondRun::incrementAndGet);

        assertThat(secondRun).hasValue(1);
    }

    @Test
    void skipsThePollCycleWhileAnotherReplicaHoldsTheLock() throws InterruptedException {
        ShedLockPollCycleGate gate = gate(DESTINATIONS);
        CountDownLatch insideCycle = new CountDownLatch(1);
        CountDownLatch releaseCycle = new CountDownLatch(1);

        Thread holder = new Thread(() -> gate.runPollCycle(() -> {
            insideCycle.countDown();
            await(releaseCycle);
        }));
        holder.start();
        assertThat(insideCycle.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicInteger contendingRuns = new AtomicInteger();
        gate.runPollCycle(contendingRuns::incrementAndGet);
        assertThat(contendingRuns).hasValue(0);

        releaseCycle.countDown();
        holder.join(2000);

        AtomicInteger afterRelease = new AtomicInteger();
        gate.runPollCycle(afterRelease::incrementAndGet);
        assertThat(afterRelease).hasValue(1);
    }

    @Test
    void releasesTheLockEvenIfThePollCycleThrows() {
        ShedLockPollCycleGate gate = gate(DESTINATIONS);

        assertThatThrownBy(() -> gate.runPollCycle(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        AtomicInteger nextRun = new AtomicInteger();
        gate.runPollCycle(nextRun::incrementAndGet);
        assertThat(nextRun).hasValue(1);
    }

    @Test
    void theLockNameIsStableRegardlessOfDestinationOrder() {
        List<UUID> reversed = List.of(DESTINATIONS.get(1), DESTINATIONS.get(0));
        ShedLockPollCycleGate gateForward = gate(DESTINATIONS);
        ShedLockPollCycleGate gateReversed = gate(reversed);

        CountDownLatch inForward = new CountDownLatch(1);
        CountDownLatch releaseForward = new CountDownLatch(1);
        Thread holder = new Thread(() -> gateForward.runPollCycle(() -> {
            inForward.countDown();
            await(releaseForward);
        }));
        holder.start();
        await(inForward);

        AtomicInteger reversedRuns = new AtomicInteger();
        gateReversed.runPollCycle(reversedRuns::incrementAndGet);
        assertThat(reversedRuns).hasValue(0); // same lock name -> blocked by the forward-order holder

        releaseForward.countDown();
        try {
            holder.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void differentDestinationSetsUseDifferentLocks() {
        ShedLockPollCycleGate gateA = gate(DESTINATIONS);
        ShedLockPollCycleGate gateB = gate(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        CountDownLatch inA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        Thread holder = new Thread(() -> gateA.runPollCycle(() -> {
            inA.countDown();
            await(releaseA);
        }));
        holder.start();
        await(inA);

        AtomicInteger bRuns = new AtomicInteger();
        gateB.runPollCycle(bRuns::incrementAndGet);
        assertThat(bRuns).hasValue(1); // different lock, not blocked by A

        releaseA.countDown();
        try {
            holder.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
