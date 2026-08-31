package com.example.gewerbeamt.send;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The JSON body of {@code POST /api/gewerbeanmeldungen}. Nothing
 * FIT-Connect-specific - just what this sample needs to build a toy
 * Gewerbeanmeldung document.
 *
 * @param destinationId  the FIT-Connect Zustellpunkt (destination) to send
 *                       to. Required: there is no configured fallback, every
 *                       send has to name its destination. In a real app you
 *                       would resolve this from a LeiKa key + region via the
 *                       SDK's {@code RouterClient} (out of scope for the
 *                       starter) or from your own configuration.
 * @param businessName   name of the business being registered
 * @param ownerName      name of the person registering it
 * @param applicantEmail where the authority should send its reply
 */
public record GewerbeanmeldungRequest(
        @NotNull UUID destinationId,
        @NotBlank String businessName,
        @NotBlank String ownerName,
        @NotBlank @Email String applicantEmail) {
}
