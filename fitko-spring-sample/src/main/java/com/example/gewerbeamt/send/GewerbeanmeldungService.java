package com.example.gewerbeamt.send;

import com.example.gewerbeamt.Leistung;
import com.gfi.ozg.fitko.spring.send.SubmissionSendException;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import com.gfi.ozg.fitko.spring.send.AttachmentToSend;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Sends a Gewerbeanmeldung through FIT-Connect.
 *
 * <p>This is the entire send-side integration: inject {@link SubmissionSender}
 * (auto-configured by {@code fitko-spring} because
 * {@code fitconnect.sender.client-id}/{@code client-secret} are set), build
 * an {@link SubmissionToSend}, call {@link SubmissionSender#send}. No
 * {@code ClientFactory}, no {@code ApplicationConfig}, no key handling.
 */
@Service
public class GewerbeanmeldungService {

    private static final Logger log = LoggerFactory.getLogger(GewerbeanmeldungService.class);

    private final SubmissionSender submissionSender;

    public GewerbeanmeldungService(SubmissionSender submissionSender) {
        this.submissionSender = submissionSender;
    }

    /**
     * @return the FIT-Connect submission and case ids assigned to the sent submission
     * @throws SubmissionSendException if FIT-Connect rejected or could not deliver it
     */
    public SentSubmission submit(GewerbeanmeldungRequest request) {
        String xmlPayload = renderXml(request);

        SubmissionToSend submission = SubmissionToSend.builder(
                        Leistung.GEWERBEANMELDUNG_LEIKA,
                        Leistung.GEWERBEANMELDUNG_NAME,
                        DataFormat.XML,
                        xmlPayload,
                        URI.create(Leistung.GEWERBEANMELDUNG_SCHEMA))
                // Required - no configured fallback destination.
                .destinationId(request.destinationId())
                // Ask the authority to reply by e-mail. Optional; at most one
                // reply channel. Sending replies from the receiving side is
                // out of scope for the starter.
                .replyChannelEmail(request.applicantEmail())
                // Optional: attach supporting documents. Held in memory -
                // large/chunked attachments are out of scope for the starter.
                .attachment(AttachmentToSend.ofBytes(
                        ("Personalausweis-Kopie fuer " + request.ownerName()).getBytes(StandardCharsets.UTF_8),
                        "text/plain",
                        "ausweis.txt"))
                .build();

        try {
            SentSubmission sent = submissionSender.send(submission);
            log.info("Sent Gewerbeanmeldung for '{}' to destination {}: submissionId={}, caseId={}",
                    request.businessName(), request.destinationId(),
                    sent.getSubmissionId(), sent.getCaseId());
            return sent;
        } catch (SubmissionSendException e) {
            // FIT-Connect rejected it or could not deliver it. Retry / dead-letter
            // as your process requires; here we just let it propagate.
            log.warn("Could not send Gewerbeanmeldung for '{}' to destination {}",
                    request.businessName(), request.destinationId(), e);
            throw e;
        }
    }

    /**
     * Renders a throwaway XML document. A real integration produces a
     * document valid against the service's actual XFall / XGewerbeanzeige
     * schema, and would likely take that document as the request body rather
     * than build it here.
     */
    private String renderXml(GewerbeanmeldungRequest request) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Gewerbeanmeldung xmlns="https://example.org/schema/gewerbeanmeldung/v1">
                    <Betrieb>%s</Betrieb>
                    <Inhaber>%s</Inhaber>
                    <Kontakt>%s</Kontakt>
                </Gewerbeanmeldung>
                """.formatted(
                escape(request.businessName()),
                escape(request.ownerName()),
                escape(request.applicantEmail()));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
