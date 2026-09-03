package com.gfi.ozg.fitko.spring.it.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * Maps the {@link ITCredentials} environment variables onto {@code fitconnect.*}
 * properties, for a test's {@code @DynamicPropertySource} method to delegate
 * to:
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void fitconnect(DynamicPropertyRegistry registry) {
 *     ITProperties.registerBase(registry);
 * }
 * }</pre>
 *
 * <p>Only credential/destination properties are set here. Polling timing,
 * {@code default-outcome} and the validation switches live in
 * {@code application.yaml} (common defaults) or a test's own
 * {@code @SpringBootTest(properties = ...)} (per-test overrides) - a
 * {@code @DynamicPropertySource} value would otherwise win over those and
 * make them impossible to override.
 *
 * <p>A key variable may hold a Spring resource location ({@code file:...},
 * {@code classpath:...}) or a raw JWK JSON string (handy for CI secrets); a
 * raw string is written to a private temp file and referenced as {@code
 * file:...}.
 */
public final class ITProperties {

    private ITProperties() {
    }

    public static void registerBase(DynamicPropertyRegistry registry) {
        // The class is being skipped by AbstractRoundTripIT#requireCredentials;
        // the context will not actually be built, so just don't blow up here.
        if (!ITCredentials.allSet(ITCredentials.BASE_VARS)) {
            return;
        }
        registry.add("fitconnect.environment", ITCredentials::environment);

        registry.add("fitconnect.sender.client-id", ITCredentials::senderClientId);
        registry.add("fitconnect.sender.client-secret", ITCredentials::senderClientSecret);

        registry.add("fitconnect.receiver.client-id", ITCredentials::receiverClientId);
        registry.add("fitconnect.receiver.client-secret", ITCredentials::receiverClientSecret);

        registry.add("fitconnect.receiver.destinations[0].id", () -> ITCredentials.destinationId().toString());
        registry.add("fitconnect.receiver.destinations[0].signing-key",
                () -> keyResource("it-d1-signing", ITCredentials.signingKey()));
        registry.add("fitconnect.receiver.destinations[0].decryption-keys[0]",
                () -> keyResource("it-d1-decryption", ITCredentials.decryptionKey()));
    }

    /** Adds a second configured destination ({@code destinations[1]}) from the {@code *_DESTINATION2_*} variables. */
    public static void registerSecondDestination(DynamicPropertyRegistry registry) {
        if (!ITCredentials.allSet(ITCredentials.SECOND_DESTINATION_VARS)) {
            return;
        }
        registry.add("fitconnect.receiver.destinations[1].id", () -> ITCredentials.secondDestinationId().toString());
        registry.add("fitconnect.receiver.destinations[1].signing-key",
                () -> keyResource("it-d2-signing", ITCredentials.secondSigningKey()));
        registry.add("fitconnect.receiver.destinations[1].decryption-keys[0]",
                () -> keyResource("it-d2-decryption", ITCredentials.secondDecryptionKey()));
    }

    static String keyResource(String name, String rawValue) {
        String value = rawValue.strip();
        if (!value.startsWith("{")) {
            return value; // already a resource location
        }
        try {
            Path file = Files.createTempFile("fitko-spring-" + name + "-", ".json");
            file.toFile().deleteOnExit();
            trySetOwnerOnly(file);
            Files.writeString(file, value);
            return "file:" + file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not materialise inline JWK for " + name, e);
        }
    }

    private static void trySetOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem - the temp dir is already user-scoped
        }
    }

    /** A random destination id for tests that only need the shape of a configured destination, not a real one. */
    public static String randomDestinationId() {
        return UUID.randomUUID().toString();
    }
}
