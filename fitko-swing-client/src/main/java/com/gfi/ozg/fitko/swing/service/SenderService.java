package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.ProblemInfo;
import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.service.SenderClients.Sender;
import dev.fitko.fitconnect.rest.model.submission.SentSubmission;
import dev.fitko.fitconnect.sdk.api.OutgoingSubmission;
import dev.fitko.fitconnect.sdk.api.event.CaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The SDK calls the UI makes. Every method blocks on the network - call them
 * off the Swing event thread. Each call uses the {@link Sender} of the
 * connection a submission was sent with, so an old submission's status is
 * still checked with the right credentials after the active connection
 * changed.
 */
public class SenderService {

    private static final Logger log = LoggerFactory.getLogger(SenderService.class);

    private final SenderClients clients;
    private final Function<String, Connection> connectionLookup;

    public SenderService(SenderClients clients, Function<String, Connection> connectionLookup) {
        this.clients = clients;
        this.connectionLookup = connectionLookup;
    }

    public SenderClients clients() {
        return clients;
    }

    /**
     * Sends one submission. Never throws for an SDK/validation failure: the
     * returned record then has status {@link RecordStatus#SEND_FAILED} and
     * the error, so failures show up in the history too.
     *
     * @param counter value for the {@code ${counter}} placeholder
     */
    public SubmissionRecord send(SendRequest request, long counter) {
        SubmissionRecord record = newRecord(request);
        try {
            String payload = PayloadTemplate.resolve(request.form().payloadTemplate, counter, request.caseId());
            record.payload = payload;
            SubmissionDraft draft = new SubmissionDraft(
                    request.destinationId(),
                    request.form().serviceId,
                    request.form().serviceName,
                    request.form().serviceRegion,
                    request.form().dataFormat,
                    SdkConfig.isBlank(request.form().dataSchema) ? null : URI.create(request.form().dataSchema.trim()),
                    payload,
                    request.form().attachments,
                    request.form().replyChannelEmail,
                    request.caseId());
            OutgoingSubmission submission = SubmissionFactory.build(draft);
            log.info("Sending submission (service {}) from destination {} to destination {}", draft.serviceId(),
                    request.connection().fromDestinationId, draft.destinationId());
            SentSubmission sent = clients.get(request.connection()).send(submission);
            record.applySent(sent);
            record.status = RecordStatus.SUBMITTED;
            log.info("Sent: submissionId={} caseId={}", sent.submissionId(), sent.caseId());
        } catch (RuntimeException e) {
            log.warn("Sending to {} failed", request.destinationId(), e);
            record.status = RecordStatus.SEND_FAILED;
            record.error = describe(e);
        }
        return record;
    }

    private static SubmissionRecord newRecord(SendRequest request) {
        SubmissionRecord record = new SubmissionRecord();
        record.sentAt = Instant.now();
        record.presetName = blankToNull(request.form().name);
        record.connectionId = request.connection().id;
        record.connectionLabel = request.connection().label;
        record.environment = request.connection().environment;
        if (request.zustellpunkt() != null) {
            record.zustellpunktId = request.zustellpunkt().id;
            record.zustellpunktLabel = request.zustellpunkt().label;
        }
        record.destinationId = request.destinationId();
        record.fromDestinationId = request.connection().fromDestinationId;
        record.caseId = request.caseId();
        record.serviceId = request.form().serviceId;
        record.serviceName = request.form().serviceName;
        record.serviceRegion = request.form().serviceRegion;
        record.dataFormat = request.form().dataFormat;
        record.dataSchema = request.form().dataSchema;
        record.replyChannelEmail = blankToNull(request.form().replyChannelEmail);
        for (AttachmentSpec attachment : request.form().attachments) {
            record.attachments.add(attachment.copy());
        }
        return record;
    }

    /** Result of a status check, applied to the record on the event thread. */
    public record StatusResult(RecordStatus status, String issuer, Instant issuedAt, List<ProblemInfo> problems,
                               String error, Instant checkedAt) {

        /** @return {@code true} if the status changed */
        public boolean applyTo(SubmissionRecord record) {
            RecordStatus before = record.status;
            record.lastCheckedAt = checkedAt;
            record.lastCheckError = error;
            if (error == null) {
                record.status = status;
                record.statusIssuer = issuer;
                record.statusIssuedAt = issuedAt;
                record.problems = new ArrayList<>(problems);
            }
            return before != record.status;
        }
    }

    public StatusResult status(SubmissionRecord record) {
        Instant now = Instant.now();
        try {
            CaseEvent latest = client(record).logOf(record.toSentSubmission()).latest();
            List<ProblemInfo> problems = latest.problems() == null ? List.of()
                    : latest.problems().stream().map(ProblemInfo::of).toList();
            return new StatusResult(RecordStatus.of(latest.state()), latest.issuer(), latest.issueTime(), problems,
                    null, now);
        } catch (RuntimeException e) {
            log.warn("Status check for submission {} failed", record.submissionId, e);
            return new StatusResult(record.status, null, null, List.of(), describe(e), now);
        }
    }

    public List<CaseEvent> eventLog(SubmissionRecord record) {
        return client(record).logOf(record.toSentSubmission()).entries();
    }

    /** Fetches the destination's encryption key id - succeeds only if the destination exists and is usable. */
    public String checkDestination(Connection connection, UUID destinationId) {
        return clients.get(connection).activeEncryptionKeyOf(destinationId).keyId();
    }

    private Sender client(SubmissionRecord record) {
        Connection connection = connectionLookup.apply(record.connectionId);
        if (connection == null) {
            throw new IllegalStateException("The connection this submission was sent with ('" + record.connectionLabel
                    + "') no longer exists");
        }
        return clients.get(connection);
    }

    /** The exception message plus its causes - the SDK's top-level messages are often generic. */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 5) {
            String message = current.getMessage();
            if (message != null && !message.isBlank() && sb.indexOf(message) < 0) {
                if (sb.length() > 0) {
                    sb.append("\n  caused by: ");
                }
                sb.append(message);
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return sb.length() == 0 ? e.getClass().getSimpleName() : sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
