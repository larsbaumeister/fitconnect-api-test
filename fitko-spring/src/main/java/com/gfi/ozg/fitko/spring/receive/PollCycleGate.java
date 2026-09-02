package com.gfi.ozg.fitko.spring.receive;

/**
 * Wraps each scheduled poll cycle of {@link SubmissionPollingService}, so an
 * optional cross-replica lock can be slotted in without the poller itself
 * depending on the locking library.
 *
 * <p>{@link #DIRECT} runs the cycle straight away and is what the poller uses
 * unless the ShedLock integration is on the classpath and a {@code
 * LockProvider} bean exists - see {@code FitConnectPollLockAutoConfiguration}
 * and {@code ShedLockPollCycleGate}. Implementations are invoked only on the
 * single {@code fitconnect-poller} thread.
 */
@FunctionalInterface
public interface PollCycleGate {

    /**
     * Runs {@code pollCycle}, or skips it if this gate decides another
     * replica should run it instead. Must not propagate an exception from
     * {@code pollCycle} beyond what {@code pollCycle} itself throws.
     */
    void runPollCycle(Runnable pollCycle);

    /** Runs every cycle immediately, with no coordination. */
    PollCycleGate DIRECT = Runnable::run;
}
