package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;

/** Sends an Antrag through FIT-Connect. Auto-configured as a bean when {@code fitconnect.sender.enabled=true}. */
public interface AntragSender {

    /**
     * @throws AntragSendException if FIT-Connect rejected or could not deliver the submission
     * @throws IllegalStateException if {@code antrag} has no destination id and none is configured
     *         via {@code fitconnect.destination-id}
     */
    SentSubmission send(AntragToSend antrag);
}
