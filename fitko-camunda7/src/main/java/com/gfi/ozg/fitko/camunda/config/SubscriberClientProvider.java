package com.gfi.ozg.fitko.camunda.config;

import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.EnvironmentName;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and caches the single {@link SubscriberClient} the delegates share.
 *
 * <p>The SDK bakes one client-id/secret and one signing/decryption key set
 * into each {@code SubscriberClient} instance, and creating one is expensive
 * (key parsing, OIDC discovery), so it is built lazily on first use and then
 * reused. The client is thread-safe for the read-only calls this application
 * makes ({@code getAvailableSubmissionsForDestination}, {@code
 * requestSubmission}, {@code acceptSubmission}, {@code rejectSubmission}).
 *
 * <p>Key locations accept {@code classpath:}, {@code file:} or a bare
 * filesystem path.
 */
@ApplicationScoped
@Slf4j
public class SubscriberClientProvider {

    private final FitConnectCamundaConfig config;

    private volatile SubscriberClient client;

    @Inject
    public SubscriberClientProvider(FitConnectCamundaConfig config) {
        this.config = config;
    }

    /** The shared client, created on first call. */
    public SubscriberClient getClient() {
        SubscriberClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = createClient();
                    client = local;
                }
            }
        }
        return local;
    }

    private SubscriberClient createClient() {
        log.info("Initialising FIT-Connect SubscriberClient for environment {} / destination {}",
                config.environment(), config.destinationId());

        List<JWK> decryptionKeys = new ArrayList<>();
        for (String path : config.decryptionKeyPaths()) {
            decryptionKeys.add(readJwk(path));
        }
        if (decryptionKeys.isEmpty()) {
            throw new IllegalStateException(
                    FitConnectCamundaConfig.DECRYPTION_KEY_PATHS + " must list at least one JWK");
        }

        SubscriberConfig subscriberConfig = SubscriberConfig.builder()
                .clientId(config.clientId())
                .clientSecret(config.clientSecret())
                .privateSigningKey(readJwk(config.signingKeyPath()))
                .privateDecryptionKeys(decryptionKeys)
                .build();

        ApplicationConfig applicationConfig = ApplicationConfig.builder()
                .activeEnvironment(new EnvironmentName(config.environment()))
                .subscriberConfig(subscriberConfig)
                .build();

        try {
            return ClientFactory.createSubscriberClient(applicationConfig);
        } catch (FitConnectInitialisationException e) {
            throw new IllegalStateException("Could not initialise the FIT-Connect SubscriberClient", e);
        }
    }

    private JWK readJwk(String location) {
        try {
            String json = new String(readBytes(location), StandardCharsets.UTF_8);
            return JWK.parse(json);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read JWK from '" + location + "'", e);
        } catch (ParseException e) {
            throw new IllegalStateException("'" + location + "' is not a valid JWK", e);
        }
    }

    private byte[] readBytes(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("classpath resource not found: " + resource);
                }
                return in.readAllBytes();
            }
        }
        String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
        return Files.readAllBytes(Path.of(path));
    }
}
