package com.gfi.ozg.ficon.processstarter;

import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;

import java.util.List;
import java.util.Objects;

/**
 * Thrown from {@link ProcessStarter#start} when an Antrag can <em>never</em>
 * be processed (e.g. its content fails a business check). {@code
 * AntragReceiveListener} then rejects the submission on FIT-Connect with
 * {@link #getProblems()} - which tells the sender why and deletes it from the
 * delivery service - and records the rejection with {@link #getMessage()} as
 * its reason.
 *
 * <p>Only for permanent failures. Any other exception from {@code start}
 * (Camunda unreachable, a timeout, ...) counts as transient: the submission
 * stays on the delivery service and is retried on a later poll cycle.
 */
public class ProcessStartRejectedException extends RuntimeException {

    private final List<Problem> problems;

    /**
     * @param reason why it was rejected - recorded in the inbox, not sent
     * @param problems what is sent to FIT-Connect with the reject - at least one
     */
    public ProcessStartRejectedException(String reason, List<Problem> problems) {
        super(reason);
        Objects.requireNonNull(problems, "problems must not be null");
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("at least one Problem is required to reject a submission");
        }
        this.problems = List.copyOf(problems);
    }

    public ProcessStartRejectedException(String reason, Problem... problems) {
        this(reason, List.of(problems));
    }

    public List<Problem> getProblems() {
        return problems;
    }
}
