package com.gfi.ozg.fitko.spring.it.support;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the payloads sent during the round-trip tests, and finds the
 * correlation marker again on the way back.
 *
 * <p>Every submission carries a unique marker like
 * {@code fitko-spring-it/PlainRoundTripIT/1b8f...-...} in its data. The
 * primary correlation is still the {@code submissionId} returned by
 * {@code send()}; the marker is what the {@code @AfterEach} orphan sweep uses
 * to recognise (and clean up) submissions this suite produced but a crash
 * left behind before they could be accepted.
 */
public final class Payloads {

    public static final String MARKER_PREFIX = "fitko-spring-it";

    private static final Pattern MARKER = Pattern.compile(MARKER_PREFIX + "/[A-Za-z0-9_]+/[0-9a-fA-F-]{36}");

    private Payloads() {
    }

    public static String newMarker(Class<?> testClass) {
        return MARKER_PREFIX + "/" + testClass.getSimpleName() + "/" + UUID.randomUUID();
    }

    public static String xml(String marker) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <roundTrip xmlns="https://fitko-spring.example/it/v1">
                    <marker>%s</marker>
                    <payload>Gewerbeanmeldung round-trip test</payload>
                </roundTrip>
                """.formatted(marker);
    }

    public static String json(String marker) {
        return """
                {"marker":"%s","payload":"Gewerbeanmeldung round-trip test"}
                """.formatted(marker).strip();
    }

    /** Not well-formed XML - for the auto-reject path in SchemaValidationRoundTripIT. */
    public static String malformedXml(String marker) {
        return "<roundTrip><marker>" + marker + "</marker><payload>unterminated";
    }

    public static byte[] attachmentText(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Deterministic filler of {@code size} bytes so an assertion can compare content exactly. */
    public static byte[] attachmentOfSize(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('A' + (i % 26));
        }
        return bytes;
    }

    /**
     * A minimal Governikus-style {@code IdentificationReport} dataSet payload,
     * enough to assert the {@code levelOfAssurance} and {@code subjectRef}
     * survive the round trip (see notes/submission-identity-routing-trust.md).
     */
    public static String identificationReport(String subjectRef, String levelOfAssurance) {
        return """
                {
                  "identificationTime": "2026-01-15T10:30:00Z",
                  "serverIdentityInformation": { "identityProvider": "https://id.test.example/idp" },
                  "identificationValues": { "subjectRef": "%s" },
                  "levelOfAssurance": "%s"
                }
                """.formatted(subjectRef, levelOfAssurance).strip();
    }

    public static String identificationReportSchemaUri() {
        return "https://schema.fitko.de/fim/identification_report_v1.0.schema.json";
    }

    public static boolean containsMarker(String data, String marker) {
        return data != null && data.contains(marker);
    }

    public static boolean containsAnySuiteMarker(String data) {
        return data != null && MARKER.matcher(data).find();
    }

    public static Optional<String> findMarker(String data) {
        if (data == null) {
            return Optional.empty();
        }
        Matcher m = MARKER.matcher(data);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }
}
