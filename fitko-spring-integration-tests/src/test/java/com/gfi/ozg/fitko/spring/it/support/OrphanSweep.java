package com.gfi.ozg.fitko.spring.it.support;

import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestinations;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts every submission still sitting on a configured destination whose
 * data carries a {@link Payloads#MARKER_PREFIX} marker - i.e. one this suite
 * produced but a failure left behind before it could be resolved. Run from
 * {@code @AfterEach}/{@code @AfterAll} so a shared TEST destination does not
 * slowly fill up with test submissions.
 *
 * <p>It only ever touches submissions with a suite marker; anything else on
 * the destination is left untouched. Every error is swallowed - a sweep
 * failure must never fail or mask a test.
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

    private static int sweepOne(ReceivingDestination destination) {
        int accepted = 0;
        try {
            for (SubmissionForPickup pickup : destination.client()
                    .getAvailableSubmissionsForDestination(destination.destinationId(), 0, PAGE)) {
                if (acceptIfSuiteSubmission(destination, pickup.getSubmissionId())) {
                    accepted++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Orphan sweep could not list destination {}: {}",
                    destination.destinationId(), e.toString());
        }
        return accepted;
    }

    private static boolean acceptIfSuiteSubmission(ReceivingDestination destination, java.util.UUID submissionId) {
        try {
            ReceivedSubmission submission = destination.client().requestSubmission(submissionId);
            if (!Payloads.containsAnySuiteMarker(safeData(submission))) {
                return false;
            }
            submission.acceptSubmission();
            log.info("Orphan sweep accepted leftover submission {} on destination {}",
                    submissionId, destination.destinationId());
            return true;
        } catch (RuntimeException e) {
            log.warn("Orphan sweep could not process submission {}: {}", submissionId, e.toString());
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
