package com.example.gewerbeamt.receive;

import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A second listener on the same event stream, unfiltered: it sees every
 * submission regardless of service, for audit logging.
 *
 * <p>Shows two things:
 * <ul>
 *   <li>{@code @SubmissionEventListener} with no {@code serviceIds} = every submission;</li>
 *   <li>multiple listeners coexist. They run in {@code @Order} order on the
 *       poller thread; this one runs first (lower order) so the audit line is
 *       written before {@link GewerbeanmeldungHandler} accepts/rejects.</li>
 * </ul>
 *
 * <p>This listener deliberately does not call {@code accept()} / {@code reject()} -
 * resolving the submission is the domain handler's job, not the auditor's.
 */
@Component
public class SubmissionAuditListener {

    private static final Logger log = LoggerFactory.getLogger(SubmissionAuditListener.class);

    @SubmissionEventListener
    @Order(0)
    public void audit(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();
        log.info("AUDIT received submission={} case={} destination={} service={} mimeType={} bytes={}",
                submission.getSubmissionId(),
                submission.getCaseId(),
                submission.getDestinationId(),
                submission.getServiceType().getIdentifier(),
                submission.getDataMimeType(),
                submission.getDataAsBytes().length);
    }
}
