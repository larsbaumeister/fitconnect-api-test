package com.example.gewerbeamt;

/**
 * The one administrative service ("Leistung") this sample deals with:
 * Gewerbeanmeldung (registering a business).
 *
 * <p>The LeiKa key identifies the service to FIT-Connect. The same string is
 * used on both sides of this app:
 * <ul>
 *   <li>as the {@code serviceId} when building an {@code SubmissionToSend}, and</li>
 *   <li>as {@code @SubmissionEventListener(serviceIds = ...)} to route incoming
 *       submissions to the right handler.</li>
 * </ul>
 * It must be a compile-time constant because an annotation attribute
 * references it.
 */
public final class Leistung {

    /** LeiKa-Leistungsschluessel for "Gewerbeanmeldung". */
    public static final String GEWERBEANMELDUNG_LEIKA = "urn:de:fim:leika:leistung:99050035001000";

    /** Human-readable service name that travels with the submission metadata. */
    public static final String GEWERBEANMELDUNG_NAME = "Gewerbeanmeldung";

    /**
     * Schema the submission's main data document conforms to. A real
     * integration points this at the actual XFall/XGewerbeanzeige schema; the
     * sample uses a placeholder because it sends a toy XML document.
     */
    public static final String GEWERBEANMELDUNG_SCHEMA =
            "https://example.org/schema/gewerbeanmeldung/v1";

    private Leistung() {
    }
}
