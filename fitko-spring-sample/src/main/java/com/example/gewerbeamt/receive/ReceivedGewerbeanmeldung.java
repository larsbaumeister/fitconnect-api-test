package com.example.gewerbeamt.receive;

import java.time.Instant;
import java.util.UUID;

/**
 * What this sample keeps out of an incoming Gewerbeanmeldung. A real system
 * would map the submission's XML/JSON data onto its own domain model and
 * persist it; here it is just a record in an in-memory {@link ReceivedAntragStore}.
 *
 * @param submissionId FIT-Connect submission id - the natural idempotency key
 * @param caseId       FIT-Connect case id (a case can span several submissions)
 * @param destinationId the Zustellpunkt it arrived on
 * @param serviceId    LeiKa key it was submitted for
 * @param dataMimeType mime type of {@link #data}
 * @param data         the decrypted main data document, verbatim
 * @param receivedAt   when this app processed it
 */
public record ReceivedGewerbeanmeldung(
        UUID submissionId,
        UUID caseId,
        UUID destinationId,
        String serviceId,
        String dataMimeType,
        String data,
        Instant receivedAt) {
}
