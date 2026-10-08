package com.gfi.ozg.ficon;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code fitconnect.*} - what is needed to build the SDK's clients.
 *
 * @param environment the SDK environment name ({@code TEST}, {@code STAGE}, {@code PROD})
 * @param sender      optional - a {@code SenderClient} bean exists only when {@code sender.client-id} is set
 * @param receiver    the tenants and destinations to poll
 */
@ConfigurationProperties("fitconnect")
public record FitConnectProperties(@DefaultValue("TEST") String environment, Sender sender, Receiver receiver) {

    public record Sender(String clientId, String clientSecret) {
    }

    /**
     * @param clientId     one API client for all destinations
     * @param clientSecret its secret
     * @param tenants      by tenant name ({@code 101-aachen}, ...) - the name
     *                     is what is stored as {@code tenant} and what
     *                     {@code antrag-routing} is keyed by
     * @param polling      see {@link Polling}
     */
    public record Receiver(String clientId, String clientSecret,
                           @DefaultValue Map<String, Tenant> tenants,
                           @DefaultValue Polling polling) {
    }

    /** @param destinations by a free name, only for readability of the config */
    public record Tenant(@DefaultValue Map<String, Destination> destinations) {
    }

    /** One FIT-Connect Zustellpunkt with its own keys - the SDK needs one {@code SubscriberClient} per key set. */
    public record Destination(UUID id, Resource signingKey, List<Resource> decryptionKeys) {
    }

    /**
     * {@code polling.enabled}, {@code polling.initial-delay} and {@code
     * polling.interval} are read directly by {@code AntragPoller}'s
     * annotations, with their defaults there.
     *
     * @param retryCooldown how long a submission whose handling failed is
     *                      skipped by this replica before it is tried again
     */
    public record Polling(@DefaultValue("20m") Duration retryCooldown) {
    }
}
