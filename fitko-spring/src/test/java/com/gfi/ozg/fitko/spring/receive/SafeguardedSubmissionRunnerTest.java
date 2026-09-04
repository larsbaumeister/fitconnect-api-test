package com.gfi.ozg.fitko.spring.receive;

import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.cooldown.RetryCooldownStore;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientPool;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import dev.fitko.fitconnect.client.SubscriberClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SafeguardedSubmissionRunner}: it processes a
 * destination's poll page in parallel up to {@code polling.concurrency}, and
 * keeps every per-submission safeguard the earlier strictly-sequential poller
 * had - the submission-timeout (with its interrupt), the retry-cooldown
 * check/record, and the failure metrics - now applied per submission across
 * the worker threads.
 */
class SafeguardedSubmissionRunnerTest {

    private final SubmissionProcessor processor = mock(SubmissionProcessor.class);
    private final ReceivePipelineMetrics metrics = mock(ReceivePipelineMetrics.class);
    private SafeguardedSubmissionRunner runner;

    @AfterEach
    void closeRunner() {
        if (runner != null) {
            runner.close();
        }
    }

    private SafeguardedSubmissionRunner runnerWith(int concurrency, Duration submissionTimeout,
                                                   RetryCooldownStore cooldownStore) {
        FitConnectProperties.Polling polling = new FitConnectProperties.Polling();
        polling.setConcurrency(concurrency);
        polling.setSubmissionTimeout(submissionTimeout);
        runner = new SafeguardedSubmissionRunner(processor, metrics, cooldownStore, polling);
        return runner;
    }

    private static ReceivingDestination destinationWithClientPool(UUID destinationId, int poolSize) {
        return new ReceivingDestination(destinationId,
                new SubscriberClientPool(() -> mock(SubscriberClient.class), poolSize), null);
    }

    @Test
    void rejectsAConcurrencyBelowOne() {
        FitConnectProperties.Polling polling = new FitConnectProperties.Polling();
        polling.setConcurrency(0);
        assertThatThrownBy(() -> new SafeguardedSubmissionRunner(processor, metrics, RetryCooldownStore.NONE, polling))
                .isInstanceOf(FitConnectConfigurationException.class)
                .hasMessageContaining("concurrency");
    }

    @Test
    void processesAWholePageInParallelUpToTheConfiguredConcurrency() throws InterruptedException {
        int concurrency = 4;
        int pageSize = 16;
        UUID destinationId = UUID.randomUUID();
        ReceivingDestination destination = destinationWithClientPool(destinationId, concurrency);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch allStarted = new CountDownLatch(concurrency);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        when(processor.process(eq(destinationId), any(), any())).thenAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            int now = inFlight.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            allStarted.countDown();
            Thread.sleep(120);
            inFlight.decrementAndGet();
            return true;
        });

        List<UUID> page = IntStream.range(0, pageSize).mapToObj(i -> UUID.randomUUID()).toList();
        long start = System.nanoTime();
        runnerWith(concurrency, Duration.ofSeconds(5), RetryCooldownStore.NONE)
                .processPage(destinationId, destination, page);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Every submission was processed exactly once...
        verify(processor, org.mockito.Mockito.times(pageSize)).process(eq(destinationId), any(), any());
        // ...concurrently: at least `concurrency` overlapped, never more, on distinct worker threads...
        assertThat(allStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(maxObserved.get()).isEqualTo(concurrency);
        assertThat(threads).hasSizeGreaterThanOrEqualTo(concurrency);
        // ...and processPage blocked until the whole page finished, in roughly
        // ceil(16/4) * 120ms, nowhere near the 16 * 120ms a sequential run would take.
        assertThat(elapsed).isLessThan(Duration.ofMillis(120L * pageSize));
        assertThat(inFlight.get()).isZero();
    }

    @Test
    void concurrencyOfOneProcessesStrictlyOneAtATime() {
        UUID destinationId = UUID.randomUUID();
        ReceivingDestination destination = destinationWithClientPool(destinationId, 1);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        when(processor.process(eq(destinationId), any(), any())).thenAnswer(invocation -> {
            maxObserved.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(20);
            inFlight.decrementAndGet();
            return true;
        });

        List<UUID> page = IntStream.range(0, 6).mapToObj(i -> UUID.randomUUID()).toList();
        runnerWith(1, Duration.ofSeconds(5), RetryCooldownStore.NONE).processPage(destinationId, destination, page);

        assertThat(maxObserved.get()).isEqualTo(1);
    }

    @Test
    void aSlowSubmissionIsAbandonedByTheTimeoutWithoutHoldingUpTheOthers() {
        UUID destinationId = UUID.randomUUID();
        ReceivingDestination destination = destinationWithClientPool(destinationId, 4);

        UUID slow = UUID.randomUUID();
        List<UUID> fast = IntStream.range(0, 4).mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> handled = new CopyOnWriteArrayList<>();
        when(processor.process(eq(destinationId), any(), any())).thenAnswer(invocation -> {
            UUID submissionId = invocation.getArgument(2);
            if (submissionId.equals(slow)) {
                Thread.sleep(2000); // far beyond the 100ms timeout below
            }
            handled.add(submissionId);
            return true;
        });

        List<UUID> page = new ArrayList<>();
        page.add(slow);
        page.addAll(fast);

        long start = System.nanoTime();
        runnerWith(4, Duration.ofMillis(100), RetryCooldownStore.NONE).processPage(destinationId, destination, page);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(handled).containsExactlyInAnyOrderElementsOf(fast);
        assertThat(handled).doesNotContain(slow);
        verify(metrics).submissionFailed(destinationId);
    }

    @Test
    void honoursTheRetryCooldownPerSubmissionAcrossWorkerThreads() {
        UUID destinationId = UUID.randomUUID();
        ReceivingDestination destination = destinationWithClientPool(destinationId, 4);

        UUID coolingDown = UUID.randomUUID();
        UUID processable = UUID.randomUUID();

        RetryCooldownStore cooldownStore = mock(RetryCooldownStore.class);
        when(cooldownStore.isCoolingDown(coolingDown)).thenReturn(true);
        when(cooldownStore.isCoolingDown(processable)).thenReturn(false);
        when(processor.process(eq(destinationId), any(), eq(processable))).thenReturn(false);

        runnerWith(4, Duration.ofSeconds(5), cooldownStore)
                .processPage(destinationId, destination, List.of(coolingDown, processable));

        // The cooling-down submission is never fetched...
        verify(processor, org.mockito.Mockito.never()).process(eq(destinationId), any(), eq(coolingDown));
        // ...the other one is, and its (failed) outcome is fed back to the store.
        verify(processor).process(eq(destinationId), any(), eq(processable));
        verify(cooldownStore).recordOutcome(processable, false);
    }
}
