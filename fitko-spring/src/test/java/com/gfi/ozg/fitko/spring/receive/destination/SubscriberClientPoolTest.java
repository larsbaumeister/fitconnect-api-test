package com.gfi.ozg.fitko.spring.receive.destination;

import dev.fitko.fitconnect.client.SubscriberClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link SubscriberClientPool}: it hands out one {@link
 * SubscriberClient} per concurrent unit of work for a destination, creating
 * the first eagerly and the rest lazily up to {@code maxSize}, and blocks
 * while all are checked out.
 */
class SubscriberClientPoolTest {

    private final ExecutorService workers = Executors.newCachedThreadPool();

    @AfterEach
    void stopWorkers() {
        workers.shutdownNow();
    }

    @Test
    void createsExactlyOneClientEagerlyUpFront() {
        AtomicInteger created = new AtomicInteger();
        SubscriberClientPool pool = new SubscriberClientPool(countingFactory(created), 4);

        assertThat(created).hasValue(1);
        assertThat(pool.createdCount()).isEqualTo(1);
    }

    @Test
    void reusesAnIdleClientInsteadOfCreatingANewOne() {
        AtomicInteger created = new AtomicInteger();
        SubscriberClientPool pool = new SubscriberClientPool(countingFactory(created), 4);

        SubscriberClient first = pool.withClient(c -> c);
        SubscriberClient second = pool.withClient(c -> c);

        assertThat(second).isSameAs(first);
        assertThat(pool.createdCount()).isEqualTo(1);
    }

    @Test
    void growsLazilyUpToMaxSizeUnderContentionAndNeverBeyondIt() throws InterruptedException {
        int maxSize = 4;
        AtomicInteger created = new AtomicInteger();
        SubscriberClientPool pool = new SubscriberClientPool(countingFactory(created), maxSize);

        CountDownLatch allBorrowed = new CountDownLatch(maxSize);
        CountDownLatch release = new CountDownLatch(1);
        Set<SubscriberClient> distinctClients = ConcurrentHashMap.newKeySet();
        List<Future<?>> borrowers = new ArrayList<>();
        for (int i = 0; i < maxSize; i++) {
            borrowers.add(workers.submit(() -> pool.withClient(client -> {
                distinctClients.add(client);
                allBorrowed.countDown();
                await(release);
                return null;
            })));
        }

        assertThat(allBorrowed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.createdCount()).isEqualTo(maxSize);
        assertThat(distinctClients).hasSize(maxSize);

        release.countDown();
        borrowers.forEach(SubscriberClientPoolTest::join);
        assertThat(created).hasValue(maxSize);
    }

    @Test
    void blocksWhileEveryClientIsCheckedOutThenProceedsWhenOneIsReturned() throws Exception {
        SubscriberClientPool pool = new SubscriberClientPool(countingFactory(new AtomicInteger()), 1);

        CountDownLatch holderHasClient = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        workers.submit(() -> pool.withClient(client -> {
            holderHasClient.countDown();
            await(releaseHolder);
            return null;
        }));
        assertThat(holderHasClient.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> blocked = workers.submit(() -> pool.withClient(client -> true));
        assertThatThrownBy(() -> blocked.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);

        releaseHolder.countDown();
        assertThat(blocked.get(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Regression: a failure while lazily creating a client must hand the
     * pool's permit back. Otherwise every transient client-creation error
     * would permanently shrink the pool's capacity by one, until it can no
     * longer hand out any client at all and the poller thread blocks forever
     * in {@code withClient}.
     */
    @Test
    void releasesThePermitWhenLazyClientCreationFails() throws InterruptedException {
        int maxSize = 3;
        AtomicInteger calls = new AtomicInteger();
        // call 1 = eager (ok); the next lazy creation blows up; everything after recovers.
        Supplier<SubscriberClient> flakyFactory = () -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("transient JWKS/key error while building the client");
            }
            return mock(SubscriberClient.class);
        };
        SubscriberClientPool pool = new SubscriberClientPool(flakyFactory, maxSize);

        // Hold the one eager client so the next borrow is forced down the lazy-create path.
        CountDownLatch holderHasClient = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        workers.submit(() -> pool.withClient(client -> {
            holderHasClient.countDown();
            await(releaseHolder);
            return null;
        }));
        assertThat(holderHasClient.await(5, TimeUnit.SECONDS)).isTrue();

        // This borrow acquires a permit, finds no idle client, and fails to create one.
        assertThatThrownBy(() -> pool.withClient(client -> client))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transient");

        releaseHolder.countDown();

        // If the failed creation leaked its permit, only maxSize-1 remain and this deadlocks.
        CountDownLatch allBorrowed = new CountDownLatch(maxSize);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> borrowers = new ArrayList<>();
        IntStream.range(0, maxSize).forEach(i -> borrowers.add(workers.submit(() -> pool.withClient(client -> {
            allBorrowed.countDown();
            await(release);
            return null;
        }))));

        assertThat(allBorrowed.await(5, TimeUnit.SECONDS))
                .as("all %d permits still available after a failed lazy creation", maxSize)
                .isTrue();
        release.countDown();
        borrowers.forEach(SubscriberClientPoolTest::join);
        assertThat(pool.createdCount()).isEqualTo(maxSize);
    }

    private static Supplier<SubscriberClient> countingFactory(AtomicInteger created) {
        return () -> {
            created.incrementAndGet();
            return mock(SubscriberClient.class);
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Future<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
