package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.FitConnectProperties;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.EnvironmentName;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Polls every configured destination and hands each available submission to
 * {@link AntragHandler}, one after another on Spring's single scheduler
 * thread - no parallel processing, poll cycles never overlap.
 *
 * <p>A submission whose handling failed stays on the delivery service and is
 * skipped by this replica for {@code fitconnect.receiver.polling.retry-cooldown}
 * - otherwise it would be retried on every poll cycle. The cooldown is kept
 * in memory per replica and only throttles retries. That a process is never
 * started twice is ensured by the database (see {@code SubmissionInbox}).
 */
@Component
@ConditionalOnProperty(name = "fitconnect.receiver.polling.enabled", matchIfMissing = true)
public class AntragPoller {

    private static final Logger log = LoggerFactory.getLogger(AntragPoller.class);

    /** One FIT-Connect destination and the client holding its keys. */
    record ReceivingDestination(String tenant, UUID id, SubscriberClient client) {
    }

    private final List<ReceivingDestination> destinations;
    private final AntragHandler handler;
    private final Duration retryCooldown;
    private final Map<UUID, Instant> skipUntil = new HashMap<>();

    @Autowired
    public AntragPoller(FitConnectProperties properties, AntragHandler handler) {
        this(receivingDestinations(properties), handler, properties.receiver().polling().retryCooldown());
    }

    AntragPoller(List<ReceivingDestination> destinations, AntragHandler handler, Duration retryCooldown) {
        if (destinations.isEmpty()) {
            throw new IllegalStateException("fitconnect.receiver.tenants must contain at least one destination");
        }
        this.destinations = destinations;
        this.handler = handler;
        this.retryCooldown = retryCooldown;
    }

    @Scheduled(initialDelayString = "${fitconnect.receiver.polling.initial-delay:5s}",
            fixedDelayString = "${fitconnect.receiver.polling.interval:30s}")
    public void poll() {
        Instant now = Instant.now();
        skipUntil.values().removeIf(until -> !until.isAfter(now));
        for (ReceivingDestination destination : destinations) {
            try {
                for (SubmissionForPickup pickup : destination.client().getAvailableSubmissionsForDestination(destination.id())) {
                    if (!skipUntil.containsKey(pickup.getSubmissionId())) {
                        receive(destination, pickup);
                    }
                }
            } catch (RuntimeException e) {
                // e.g. FIT-Connect unreachable - the other destinations are still polled.
                log.warn("Polling destination {} (tenant {}) failed", destination.id(), destination.tenant(), e);
            }
        }
    }

    private void receive(ReceivingDestination destination, SubmissionForPickup pickup) {
        try {
            handler.handle(destination.client().requestSubmission(pickup), destination.tenant());
        } catch (RuntimeException e) {
            skipUntil.put(pickup.getSubmissionId(), Instant.now().plus(retryCooldown));
            log.error("Submission {} failed, stays on the delivery service, next try in {}",
                    pickup.getSubmissionId(), retryCooldown, e);
        }
    }

    /** One SDK {@link SubscriberClient} per destination - the SDK binds one key set to each client. */
    private static List<ReceivingDestination> receivingDestinations(FitConnectProperties properties) {
        FitConnectProperties.Receiver receiver = properties.receiver();
        List<ReceivingDestination> destinations = new ArrayList<>();
        receiver.tenants().forEach((tenant, config) -> config.destinations().values().forEach(destination -> {
            SubscriberConfig subscriberConfig = SubscriberConfig.builder()
                    .clientId(receiver.clientId())
                    .clientSecret(receiver.clientSecret())
                    .privateSigningKey(readJwk(destination.signingKey()))
                    .privateDecryptionKeys(destination.decryptionKeys().stream().map(AntragPoller::readJwk).toList())
                    .build();
            SubscriberClient client = ClientFactory.createSubscriberClient(ApplicationConfig.builder()
                    .activeEnvironment(new EnvironmentName(properties.environment()))
                    .subscriberConfig(subscriberConfig)
                    .build());
            destinations.add(new ReceivingDestination(tenant, destination.id(), client));
        }));
        return destinations;
    }

    private static JWK readJwk(Resource resource) {
        try {
            return JWK.parse(resource.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Could not read a JWK from " + resource, e);
        }
    }
}
