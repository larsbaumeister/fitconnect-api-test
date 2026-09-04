package com.gfi.ozg.fitko.spring.receive.destination;

import dev.fitko.fitconnect.client.SubscriberClient;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * One Zustellpunkt (destination) this application receives on: its id, a
 * {@link SubscriberClientPool} of clients configured with its own keys (see
 * {@link com.gfi.ozg.fitko.spring.config.ApplicationConfigFactory}'s javadoc
 * for why every destination needs its own client, and {@link
 * SubscriberClientPool}'s for why it is a pool rather than a single instance),
 * and - if this destination is also reachable through the callback webhook
 * endpoint - the secret used to validate an incoming callback claims to be
 * for it.
 *
 * <p>Shared between {@code SubmissionPollingService} /
 * {@code SafeguardedSubmissionRunner} and {@code FitConnectCallbackController}:
 * all deliver submissions for the same set of destinations, just through
 * different triggers (a poll cycle vs. an inbound HTTP callback).
 */
public record ReceivingDestination(UUID destinationId, SubscriberClientPool clientPool, String callbackSecret) {

    public ReceivingDestination {
        Objects.requireNonNull(destinationId, "destinationId must not be null");
        Objects.requireNonNull(clientPool, "clientPool must not be null");
        // callbackSecret is deliberately nullable - not every destination has to support callbacks.
    }

    /**
     * Borrows a {@link SubscriberClient} from this destination's pool for the
     * duration of {@code action} and returns it afterwards. Callers must do
     * all their work with the client inside {@code action} and not retain it.
     */
    public <R> R withClient(Function<SubscriberClient, R> action) {
        return clientPool.withClient(action);
    }

    /** {@code true} if this destination has a callback secret configured, i.e. is reachable through the webhook endpoint. */
    public boolean supportsCallback() {
        return callbackSecret != null;
    }
}
