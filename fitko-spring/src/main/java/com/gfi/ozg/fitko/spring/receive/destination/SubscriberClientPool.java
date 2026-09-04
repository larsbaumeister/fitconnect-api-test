package com.gfi.ozg.fitko.spring.receive.destination;

import dev.fitko.fitconnect.client.SubscriberClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A small pool of {@link SubscriberClient} instances for one destination, so
 * the receive pipeline can process that destination's submissions in parallel
 * (see {@link com.gfi.ozg.fitko.spring.receive.SafeguardedSubmissionRunner}).
 *
 * <p><b>Why a pool and not one shared client:</b> the FIT-Connect SDK's {@code
 * SubscriberClient} is <em>not</em> safe for concurrent use. Every API call
 * ({@code requestSubmission}, {@code acceptSubmission}, {@code
 * getAvailableSubmissionsForDestination}, {@code validateCallback}, ...) reads
 * the OAuth bearer token from a single shared, unsynchronised holder inside
 * the SDK ({@code DefaultOAuthApiService} in SDK 3.5.0 - a plain
 * check-then-authenticate on non-{@code volatile} fields; there is no {@code
 * synchronized}/{@code Atomic*} anywhere in the SDK). Two threads calling one
 * client instance can therefore race into repeated re-authentication storms or
 * momentarily read a {@code null} token. Giving each concurrent unit of work
 * its own client instance (hence its own token holder) sidesteps that
 * entirely.
 *
 * <p><b>Lifecycle:</b> one client is created eagerly in the constructor - so a
 * bad signing/decryption key or unreadable resource still fails fast at
 * context-refresh time, exactly as before. The rest (up to {@code maxSize})
 * are created lazily the first time that many are needed at once, then
 * retained and reused. {@link #withClient} blocks while all {@code maxSize}
 * clients are checked out.
 */
@Slf4j
public final class SubscriberClientPool implements AutoCloseable {

    private final Supplier<SubscriberClient> clientFactory;
    private final int maxSize;
    private final Semaphore permits;
    private final ConcurrentLinkedQueue<SubscriberClient> idle = new ConcurrentLinkedQueue<>();
    private final List<SubscriberClient> created = new CopyOnWriteArrayList<>();

    /**
     * @param clientFactory builds a fresh {@link SubscriberClient} for this destination; called between
     *                      once (eager) and {@code maxSize} times total, never concurrently for the same pool
     * @param maxSize       the most clients this pool will ever hold, i.e. the most submissions of this
     *                      destination that can be in flight at once
     */
    public SubscriberClientPool(Supplier<SubscriberClient> clientFactory, int maxSize) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        if (maxSize < 1) {
            throw new IllegalArgumentException("SubscriberClient pool size must be >= 1, was " + maxSize);
        }
        this.maxSize = maxSize;
        this.permits = new Semaphore(maxSize);
        idle.add(newClient()); // eager first client: keeps config/key validation at context-refresh time
    }

    private SubscriberClientPool(List<SubscriberClient> fixedClients) {
        this.clientFactory = () -> {
            throw new IllegalStateException("a fixed SubscriberClientPool cannot create more clients");
        };
        this.maxSize = fixedClients.size();
        this.permits = new Semaphore(fixedClients.size());
        this.created.addAll(fixedClients);
        this.idle.addAll(fixedClients);
    }

    /**
     * A pool over a fixed set of already-built clients, with no factory and no
     * lazy growth - for tests and for a consumer that wants to supply its own
     * client instances.
     */
    public static SubscriberClientPool fixed(SubscriberClient... clients) {
        if (clients.length == 0) {
            throw new IllegalArgumentException("at least one SubscriberClient is required");
        }
        return new SubscriberClientPool(List.of(clients));
    }

    /**
     * Borrows a client, runs {@code action} with it, and returns it to the
     * pool afterwards - even if {@code action} throws. Blocks if every client
     * is currently in use.
     */
    public <R> R withClient(Function<SubscriberClient, R> action) {
        SubscriberClient client = borrow();
        try {
            return action.apply(client);
        } finally {
            idle.add(client);
            permits.release();
        }
    }

    /** The most clients this pool can hand out concurrently. */
    public int maxSize() {
        return maxSize;
    }

    /** How many clients have actually been created so far (for diagnostics/tests). */
    public int createdCount() {
        return created.size();
    }

    private SubscriberClient borrow() {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a free SubscriberClient", e);
        }
        SubscriberClient pooled = idle.poll();
        if (pooled != null) {
            return pooled;
        }
        // A permit is held and no idle client was available, so fewer than
        // maxSize clients exist -> it is safe to create one more. If that
        // creation fails (a transient key/JWKS/resource error - the eager
        // client already validated the config at startup), hand the permit
        // back before propagating, or the pool would permanently lose one
        // unit of capacity on every such failure.
        try {
            return newClient();
        } catch (RuntimeException | Error e) {
            permits.release();
            throw e;
        }
    }

    private SubscriberClient newClient() {
        SubscriberClient client = Objects.requireNonNull(clientFactory.get(), "clientFactory returned null");
        created.add(client);
        log.debug("SubscriberClient pool now holds {} client(s) (max {})", created.size(), maxSize);
        return client;
    }

    /**
     * Drops all client references. The SDK's {@code SubscriberClient} exposes
     * no {@code close()}/{@code shutdown()}; its underlying OkHttp pools are
     * daemon threads that are idle-reaped on their own.
     */
    @Override
    public void close() {
        idle.clear();
        created.clear();
    }
}
