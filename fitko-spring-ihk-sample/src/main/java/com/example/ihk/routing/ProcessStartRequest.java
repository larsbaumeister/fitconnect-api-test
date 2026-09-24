package com.example.ihk.routing;

import java.util.Map;
import java.util.UUID;

/**
 * Everything a {@link ProcessStarter} needs to start the right downstream
 * process for one accepted Antrag.
 */
public record ProcessStartRequest(
        String processKey,
        UUID submissionId,
        UUID caseId,
        String tenant,
        String leikaSchluessel,
        Map<String, Object> variables) {
}
