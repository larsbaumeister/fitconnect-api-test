package com.gfi.ozg.fitko.spring.receive;

import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.cooldown.RetryCooldownStore;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the per-submission receive work for one destination's poll page, up to
 * {@code fitconnect.receiver.polling.concurrency} submissions at a time, and
 * returns only once the whole page is done. Split out of {@link
 * SubmissionPollingService} so all of the concurrency lives here and the
 * poller keeps just scheduling, the {@link PollCycleGate} and per-destination
 * listing.
 *
 * <p><b>Every safeguard the earlier strictly-sequential version had is kept,
 * per submission:</b>
 * <ul>
 *   <li><b>retry-cooldown</b> - {@link RetryCooldownStore#isCoolingDown} is
 *       checked before a submission is fetched, {@link
 *       RetryCooldownStore#recordOutcome} after.</li>
 *   <li><b>submission-timeout</b> - each submission still runs on its own
 *       dedicated worker thread with its own full {@code
 *       polling.submission-timeout} budget and a best-effort {@code
 *       cancel(true)} interrupt if it overruns; the hung worker's stack trace
 *       is logged first so the hang point is visible, and one stuck submission
 *       never holds up the others.</li>
 *   <li><b>metrics</b> - {@code submissionProcessed} / {@code
 *       submissionFailed} exactly as before.</li>
 * </ul>
 * The ShedLock poll-cycle gate and the "a failure on one destination does not
 * stop the others" isolation stay in {@link SubmissionPollingService}; this
 * class is only reached once a page has already been listed successfully.
 *
 * <p><b>Two executors</b>, mirroring the original design: a fixed
 * <em>dispatch</em> pool of {@code concurrency} threads that governs how many
 * submissions run at once, and a cached <em>timeout</em> pool that gives every
 * in-flight submission its own interruptible thread - the only way {@link
 * Future#get(long, TimeUnit)} can put a hard, cancellable deadline on a
 * blocking SDK call (you cannot interrupt a blocking call running on your own
 * thread). {@code processPage} blocks the caller until all of a page's tasks
 * have finished, so the poll cycle - and any ShedLock lock it holds - still
 * spans the whole cycle and cycles never overlap.
 */
@Slf4j
public class SafeguardedSubmissionRunner implements AutoCloseable {

    private final SubmissionProcessor submissionProcessor;
    private final ReceivePipelineMetrics metrics;
    private final RetryCooldownStore retryCooldownStore;
    private final Duration submissionTimeout;
    private final int concurrency;
    private final ExecutorService dispatchExecutor;
    private final ExecutorService timeoutExecutor;

    public SafeguardedSubmissionRunner(SubmissionProcessor submissionProcessor, ReceivePipelineMetrics metrics,
                                       RetryCooldownStore retryCooldownStore, FitConnectProperties.Polling polling) {
        this.submissionProcessor = Objects.requireNonNull(submissionProcessor, "submissionProcessor must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.retryCooldownStore = Objects.requireNonNull(retryCooldownStore, "retryCooldownStore must not be null");
        this.submissionTimeout = polling.getSubmissionTimeout();
        this.concurrency = polling.getConcurrency();
        if (this.concurrency < 1) {
            throw new FitConnectConfigurationException(
                    "fitconnect.receiver.polling.concurrency must be >= 1, was " + this.concurrency);
        }
        this.dispatchExecutor = Executors.newFixedThreadPool(
                concurrency, namedDaemonFactory("fitconnect-poll-dispatch"));
        this.timeoutExecutor = Executors.newCachedThreadPool(
                namedDaemonFactory("fitconnect-submission-worker"));
    }

    /** How many submissions of one destination's page are processed in parallel. */
    public int concurrency() {
        return concurrency;
    }

    /**
     * Processes every id in {@code submissionIds} for {@code destination},
     * {@link #concurrency()} at a time, and returns only once all of them have
     * finished (or been abandoned for this cycle by {@code
     * submission-timeout}). Never throws - a failure to process one submission
     * is logged, recorded via {@link RetryCooldownStore} and the metrics, and
     * leaves the submission on the delivery service for a later cycle.
     */
    public void processPage(UUID destinationId, ReceivingDestination destination, List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return;
        }
        List<Future<?>> inFlight = new ArrayList<>(submissionIds.size());
        try {
            for (UUID submissionId : submissionIds) {
                inFlight.add(dispatchExecutor.submit(
                        () -> processOneWithSafeguards(destinationId, destination, submissionId)));
            }
        } catch (RejectedExecutionException e) {
            // The dispatch executor is shutting down - stop scheduling, still
            // wait out whatever was already accepted.
            log.debug("Dispatch executor rejected a submission task (shutting down); "
                    + "draining {} already in flight", inFlight.size());
        }
        awaitAll(inFlight);
    }

    private void awaitAll(List<Future<?>> inFlight) {
        for (Future<?> future : inFlight) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                inFlight.forEach(f -> f.cancel(true));
                return;
            } catch (CancellationException e) {
                // cancelled during shutdown - nothing to do
            } catch (ExecutionException e) {
                // processOneWithSafeguards is written never to throw; purely defensive.
                log.error("Unexpected failure in a submission task", e.getCause());
            }
        }
    }

    private void processOneWithSafeguards(UUID destinationId, ReceivingDestination destination, UUID submissionId) {
        if (retryCooldownStore.isCoolingDown(submissionId)) {
            return;
        }
        boolean succeeded = processWithTimeout(destinationId, destination, submissionId);
        retryCooldownStore.recordOutcome(submissionId, succeeded);
    }

    /**
     * Runs {@code submissionProcessor.process} (against a client borrowed from
     * this destination's pool) on {@link #timeoutExecutor} and waits at most
     * {@code polling.submission-timeout} for it. On timeout the hung worker's
     * current stack trace is logged (so it is visible <em>where</em> the
     * submission - typically a listener/event handler or a blocking SDK call -
     * is stuck), the worker is interrupted (best-effort - see {@code
     * FitConnectProperties.Polling#getSubmissionTimeout()}) and this returns
     * {@code false} without waiting for it any longer, so one stuck submission
     * never stalls the rest of the page.
     */
    private boolean processWithTimeout(UUID destinationId, ReceivingDestination destination, UUID submissionId) {
        AtomicReference<Thread> worker = new AtomicReference<>();
        Future<Boolean> future = timeoutExecutor.submit(() -> {
            worker.set(Thread.currentThread());
            try {
                return destination.withClient(
                        client -> submissionProcessor.process(destinationId, client, submissionId));
            } finally {
                worker.set(null);
            }
        });
        try {
            return future.get(submissionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            metrics.submissionFailed(destinationId);
            log.error("Processing submission {} exceeded the {} submission-timeout, abandoning it for this cycle.{}",
                    submissionId, submissionTimeout, hungWorkerStackTrace(worker.get()));
            future.cancel(true);
            return false;
        } catch (ExecutionException e) {
            // submissionProcessor.process() is documented to never throw, so
            // this is a defensive fallback, not an expected path.
            metrics.submissionFailed(destinationId);
            log.error("Unexpected exception processing submission {}", submissionId, e.getCause());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("Interrupted while waiting for submission {} to be processed", submissionId);
            return false;
        }
    }

    @Override
    public void close() {
        dispatchExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }

    /**
     * Best-effort snapshot of where a timed-out submission worker is currently
     * blocked, captured before {@code cancel(true)} interrupts it so the frames
     * show the real hang point rather than interrupt handling. Returns an empty
     * string if the worker already finished in the race with the timeout firing
     * (its stack is then meaningless).
     */
    private static String hungWorkerStackTrace(Thread worker) {
        if (worker == null) {
            return "";
        }
        StackTraceElement[] frames = worker.getStackTrace();
        if (frames.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" Worker \"").append(worker.getName())
                .append("\" is ").append(worker.getState()).append(", stuck at:");
        for (StackTraceElement frame : frames) {
            sb.append("\n\tat ").append(frame);
        }
        return sb.toString();
    }

    private static java.util.concurrent.ThreadFactory namedDaemonFactory(String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
