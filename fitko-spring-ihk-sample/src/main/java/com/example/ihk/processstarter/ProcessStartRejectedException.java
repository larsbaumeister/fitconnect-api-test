package com.example.ihk.processstarter;

import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;

import java.util.List;
import java.util.Objects;

/**
 * Thrown from {@link ProcessStarter#start} when an Antrag can <em>never</em>
 * be processed (e.g. its content fails a business check) - {@code
 * AntragRoutingListener} then rejects the submission with {@link
 * #getProblems()}, which tells the sender why and deletes it from the
 * delivery service.
 *
 * <p>Only for permanent failures. Any other exception from {@code start}
 * (Camunda unreachable, a timeout, ...) counts as transient: the submission
 * stays unresolved and is retried on a later poll cycle.
 */
public class ProcessStartRejectedException extends RuntimeException {

    private final List<Problem> problems;

    public ProcessStartRejectedException(String message, List<Problem> problems) {
        super(message);
        Objects.requireNonNull(problems, "problems must not be null");
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("at least one Problem is required to reject a submission");
        }
        this.problems = List.copyOf(problems);
    }

    public ProcessStartRejectedException(String message, Problem... problems) {
        this(message, List.of(problems));
    }

    public List<Problem> getProblems() {
        return problems;
    }
}
