package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.client.SubscriberClient;

import java.util.Objects;
import java.util.UUID;

/**
 * One Zustellpunkt (destination) this application receives on: its id, the
 * {@link SubscriberClient} configured with its own keys (see {@link
 * com.gfi.ozg.fitko.spring.config.ApplicationConfigFactory}'s javadoc for
 * why every destination needs its own client instead of sharing one), and -
 * if this destination is also reachable through the callback webhook
 * endpoint - the secret used to validate an incoming callback claims to be
 * for it.
 *
 * <p>Shared between {@link AntragPollingService} and {@code
 * FitConnectCallbackController}: both deliver submissions for the same set
 * of destinations, just through different triggers (a poll cycle vs. an
 * inbound HTTP callback).
 */
public record ReceivingDestination(UUID destinationId, SubscriberClient client, String callbackSecret) {

    public ReceivingDestination {
        Objects.requireNonNull(destinationId, "destinationId must not be null");
        Objects.requireNonNull(client, "client must not be null");
        // callbackSecret is deliberately nullable - not every destination has to support callbacks.
    }

    /** {@code true} if this destination has a callback secret configured, i.e. is reachable through the webhook endpoint. */
    public boolean supportsCallback() {
        return callbackSecret != null;
    }
}
