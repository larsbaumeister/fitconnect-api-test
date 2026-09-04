package com.gfi.ozg.fitko.spring.it.support;

import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestinations;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Keeps a configured destination clean enough for the round-trip tests to run
 * against:
 *
 * <ul>
 *   <li>{@link #acceptSuiteLeftovers} - accepts every still-available
 *       submission whose data carries a {@link Payloads#MARKER_PREFIX} marker,
 *       i.e. one this suite produced but a failure left behind. Run from
 *       {@code @AfterEach}.</li>
 *   <li>{@link #clearUndecryptable} - rejects every still-available submission
 *       that cannot be downloaded/decrypted (e.g. encrypted with a key that no
 *       longer matches the destination). These are never one of ours - our
 *       submissions always decrypt - and, unlike a schema failure, a key
 *       mismatch is <em>not</em> auto-rejected by the SDK, so they otherwise
 *       clog the destination forever and starve a poll cycle that uses a
 *       small {@code polling.limit}. Run from {@code @BeforeEach}.</li>
 * </ul>
 *
 * Every error is swallowed - a sweep must never fail or mask a test.
 */
public final class OrphanSweep {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweep.class);
    private static final int PAGE = 100;

    private OrphanSweep() {
    }

    public static int acceptSuiteLeftovers(ReceivingDestinations destinations) {
        if (destinations == null) {
            return 0;
        }
        int accepted = 0;
        for (ReceivingDestination destination : destinations.all()) {
            accepted += sweepOne(destination);
        }
        if (accepted > 0) {
            log.info("Orphan sweep accepted {} leftover test submission(s)", accepted);
        }
        return accepted;
    }

    /** Rejects every available submission that cannot be downloaded/decrypted. Returns how many. */
    public static int clearUndecryptable(ReceivingDestinations destinations) {
        if (destinations == null) {
            return 0;
        }
        int rejected = 0;
        for (ReceivingDestination destination : destinations.all()) {
            rejected += clearUndecryptableOne(destination);
        }
        if (rejected > 0) {
            log.info("Cleared {} undecryptable submission(s) off the destination(s)", rejected);
        }
        return rejected;
    }

    private static int sweepOne(ReceivingDestination destination) {
        int accepted = 0;
        try {
            for (SubmissionForPickup pickup : list(destination)) {
                if (acceptIfSuiteSubmission(destination, pickup.getSubmissionId())) {
                    accepted++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Orphan sweep could not list destination {}: {}", destination.destinationId(), e.toString());
        }
        return accepted;
    }

    private static int clearUndecryptableOne(ReceivingDestination destination) {
        int rejected = 0;
        try {
            for (SubmissionForPickup pickup : list(destination)) {
                if (rejectIfUndecryptable(destination, pickup)) {
                    rejected++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Undecryptable-cleanup could not list destination {}: {}",
                    destination.destinationId(), e.toString());
        }
        return rejected;
    }

    /**
     * Downloads {@code pickup} and, if that fails, rejects it - both done with
     * the same borrowed client inside one {@link ReceivingDestination#withClient}
     * call, since the pool must not have a client retained past the callback.
     */
    private static boolean rejectIfUndecryptable(ReceivingDestination destination, SubmissionForPickup pickup) {
        return Boolean.TRUE.equals(destination.withClient(client -> {
            try {
                client.requestSubmission(pickup.getSubmissionId());
                return false; // downloaded fine - leave it (it is either ours or a real submission)
            } catch (RuntimeException downloadFailure) {
                try {
                    Problem problem = new Problem(
                            Problem.SCHEMA_URL + "technical-error",
                            "Undecryptable",
                            "fitko-spring-it: submission could not be decrypted with the configured keys; cleared by test setup",
                            "other");
                    client.rejectSubmission(pickup, List.of(problem));
                    log.info("Rejected undecryptable submission {} on destination {} ({})",
                            pickup.getSubmissionId(), destination.destinationId(), downloadFailure.getMessage());
                    return true;
                } catch (RuntimeException e) {
                    log.debug("Could not reject undecryptable submission {}: {}",
                            pickup.getSubmissionId(), e.toString());
                    return false;
                }
            }
        }));
    }

    private static List<SubmissionForPickup> list(ReceivingDestination destination) {
        return destination.withClient(
                client -> client.getAvailableSubmissionsForDestination(destination.destinationId(), 0, PAGE));
    }

    private static boolean acceptIfSuiteSubmission(ReceivingDestination destination, UUID submissionId) {
        try {
            return Boolean.TRUE.equals(destination.withClient(client -> {
                ReceivedSubmission submission = client.requestSubmission(submissionId);
                if (!Payloads.containsAnySuiteMarker(safeData(submission))) {
                    return false;
                }
                submission.acceptSubmission();
                log.info("Orphan sweep accepted leftover submission {} on destination {}",
                        submissionId, destination.destinationId());
                return true;
            }));
        } catch (RuntimeException e) {
            log.debug("Orphan sweep skipped submission {}: {}", submissionId, e.toString());
            return false;
        }
    }

    private static String safeData(ReceivedSubmission submission) {
        try {
            return submission.getDataAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
