package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;

import java.util.UUID;

/**
 * What the compose form hands to {@link SenderService#send}: the form state
 * (with the unresolved payload template), where it goes, and with which
 * credentials.
 *
 * @param form         compose-form state; {@code name} is the preset it came from, if any
 * @param zustellpunkt the saved Zustellpunkt, or {@code null} for an ad-hoc destination
 * @param connection   credentials to send with
 * @param caseId       existing case to append to, or {@code null} for a new case
 */
public record SendRequest(Preset form, Zustellpunkt zustellpunkt, UUID destinationId, Connection connection, UUID caseId) {
}
