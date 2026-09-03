package com.gfi.ozg.fitko.spring.it.support;

import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.UUID;

/**
 * The environment-variable contract for the integration suite, and the
 * skip/fail gate that keeps a credential-free checkout green.
 *
 * <p>{@link #requireBaseCredentials()} is called from {@link AbstractRoundTripIT}'s
 * {@code @BeforeAll}: with the required variables unset it aborts the test
 * class (JUnit "skipped"), unless {@code FITCONNECT_IT_STRICT=true} - set by
 * CI - in which case a missing variable is a hard failure instead.
 *
 * <p>Values are read live from {@link System#getenv} every time, so a test
 * can be pointed at a different environment/destination without a rebuild.
 */
public final class ITCredentials {

    private ITCredentials() {
    }

    /** Just the sender credentials - for the send-error tests, which do not receive. */
    public static final List<String> SENDER_VARS = List.of(
            "FITCONNECT_SENDER_CLIENT_ID",
            "FITCONNECT_SENDER_CLIENT_SECRET");

    /** Required by every round-trip IT. */
    public static final List<String> BASE_VARS = List.of(
            "FITCONNECT_SENDER_CLIENT_ID",
            "FITCONNECT_SENDER_CLIENT_SECRET",
            "FITCONNECT_RECEIVER_CLIENT_ID",
            "FITCONNECT_RECEIVER_CLIENT_SECRET",
            "FITCONNECT_IT_DESTINATION_ID",
            "FITCONNECT_IT_SIGNING_KEY",
            "FITCONNECT_IT_DECRYPTION_KEY");

    /** Extra variables for {@code MultiDestinationRoundTripIT}. */
    public static final List<String> SECOND_DESTINATION_VARS = List.of(
            "FITCONNECT_IT_DESTINATION2_ID",
            "FITCONNECT_IT_DESTINATION2_SIGNING_KEY",
            "FITCONNECT_IT_DESTINATION2_DECRYPTION_KEY");

    /** Extra variables for {@code SchemaValidationRoundTripIT}. */
    public static final List<String> REAL_SERVICE_VARS = List.of(
            "FITCONNECT_IT_SERVICE_ID",
            "FITCONNECT_IT_DATA_SCHEMA");

    // --- gate -----------------------------------------------------------------

    public static void requireBaseCredentials() {
        gate(BASE_VARS);
    }

    /** Skip (or, under strict mode, fail) unless every variable in {@code vars} is set. */
    public static void gate(List<String> vars) {
        List<String> missing = missing(vars);
        if (missing.isEmpty()) {
            return;
        }
        String message = "Integration test skipped - set these environment variables to run it: "
                + String.join(", ", missing) + " (see fitko-spring-integration-tests/README.md)";
        if (strict()) {
            throw new IllegalStateException("FITCONNECT_IT_STRICT=true, refusing to skip: " + message);
        }
        Assumptions.abort(message);
    }

    public static boolean allSet(List<String> vars) {
        return missing(vars).isEmpty();
    }

    public static List<String> missing(List<String> vars) {
        return vars.stream().filter(v -> isBlank(System.getenv(v))).toList();
    }

    public static boolean strict() {
        return Boolean.parseBoolean(env("FITCONNECT_IT_STRICT", "false"));
    }

    // --- typed accessors ----------------------------------------------------

    public static String environment() {
        return env("FITCONNECT_ENVIRONMENT", "TEST");
    }

    public static String senderClientId() {
        return require("FITCONNECT_SENDER_CLIENT_ID");
    }

    public static String senderClientSecret() {
        return require("FITCONNECT_SENDER_CLIENT_SECRET");
    }

    public static String receiverClientId() {
        return require("FITCONNECT_RECEIVER_CLIENT_ID");
    }

    public static String receiverClientSecret() {
        return require("FITCONNECT_RECEIVER_CLIENT_SECRET");
    }

    public static UUID destinationId() {
        return UUID.fromString(require("FITCONNECT_IT_DESTINATION_ID"));
    }

    /** Raw value of {@code FITCONNECT_IT_SIGNING_KEY}: a Spring resource location or an inline JWK JSON string. */
    public static String signingKey() {
        return require("FITCONNECT_IT_SIGNING_KEY");
    }

    public static String decryptionKey() {
        return require("FITCONNECT_IT_DECRYPTION_KEY");
    }

    public static UUID secondDestinationId() {
        return UUID.fromString(require("FITCONNECT_IT_DESTINATION2_ID"));
    }

    public static String secondSigningKey() {
        return require("FITCONNECT_IT_DESTINATION2_SIGNING_KEY");
    }

    public static String secondDecryptionKey() {
        return require("FITCONNECT_IT_DESTINATION2_DECRYPTION_KEY");
    }

    /**
     * LeiKa key sent as the submission's service id. For the transport tests
     * it is an opaque label that just travels in the metadata, so it has a
     * sensible default; {@code SchemaValidationRoundTripIT} requires the real
     * one to be set.
     */
    public static String serviceId() {
        return env("FITCONNECT_IT_SERVICE_ID", "urn:de:fim:leika:leistung:99050035001000");
    }

    public static String dataSchema() {
        return env("FITCONNECT_IT_DATA_SCHEMA", "https://schema.fitko.de/fim/it/roundtrip/v1.xsd");
    }

    // --- helpers ----------------------------------------------------------------

    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return isBlank(value) ? fallback : value;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (isBlank(value)) {
            throw new IllegalStateException("Required environment variable " + name + " is not set");
        }
        return value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
