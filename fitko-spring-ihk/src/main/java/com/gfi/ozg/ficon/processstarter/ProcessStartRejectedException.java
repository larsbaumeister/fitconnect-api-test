package com.gfi.ozg.ficon.processstarter;

import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;

import java.util.List;

/**
 * Thrown by {@link ProcessStarter#start} when an Antrag can <em>never</em> be
 * processed (e.g. it fails a business check). The submission is then
 * rejected on FIT-Connect with {@link #getProblems()}, and {@link
 * #getMessage()} is recorded as the reason. Only for permanent failures -
 * anything else is retried.
 */
public class ProcessStartRejectedException extends RuntimeException {

    private final List<Problem> problems;

    /**
     * @param reason   recorded in the inbox, not sent
     * @param problems sent to FIT-Connect with the rejection - at least one
     */
    public ProcessStartRejectedException(String reason, Problem... problems) {
        super(reason);
        if (problems.length == 0) {
            throw new IllegalArgumentException("at least one Problem is required to reject a submission");
        }
        this.problems = List.of(problems);
    }

    public List<Problem> getProblems() {
        return problems;
    }
}
