package com.gfi.ozg.fitko.spring.receive.destination;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;

/**
 * Creates a {@link SubscriberClient} from an {@link ApplicationConfig}.
 *
 * <p>Exists as a seam rather than calling {@link ClientFactory#createSubscriberClient}
 * directly: {@link com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration}
 * calls this once per configured destination (each with its own keys, so its
 * own real {@code SubscriberClient}), and a test replacing this one bean can
 * substitute a mock per call instead of needing one shared mock to somehow
 * behave like several distinct clients.
 */
@FunctionalInterface
public interface SubscriberClientFactory {

    SubscriberClient create(ApplicationConfig config) throws FitConnectInitialisationException;
}
