package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;

/** Sends a submission through FIT-Connect. Auto-configured as a bean when {@code fitconnect.sender.enabled=true}. */
public interface SubmissionSender {

    /**
     * @throws SubmissionSendException if FIT-Connect rejected or could not deliver the submission
     * @throws IllegalStateException if {@code submission} has no destination id -
     *         there is no configured fallback, it must be set via {@code
     *         SubmissionToSend.builder(...).destinationId(...)}
     */
    SentSubmission send(SubmissionToSend submission);
}
