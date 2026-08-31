package com.example.gewerbeamt.receive;

import com.gfi.ozg.fitko.spring.receive.AntragEventListener;
import com.gfi.ozg.fitko.spring.receive.AntragReceivedEvent;
import com.gfi.ozg.fitko.spring.receive.ReceivedAntrag;
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
 *   <li>{@code @AntragEventListener} with no {@code serviceIds} = every Antrag;</li>
 *   <li>multiple listeners coexist. They run in {@code @Order} order on the
 *       poller thread; this one runs first (lower order) so the audit line is
 *       written before {@link GewerbeanmeldungHandler} accepts/rejects.</li>
 * </ul>
 *
 * <p>This listener deliberately does not call {@code accept()} / {@code reject()} -
 * resolving the submission is the domain handler's job, not the auditor's.
 */
@Component
public class AntragAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AntragAuditListener.class);

    @AntragEventListener
    @Order(0)
    public void audit(AntragReceivedEvent event) {
        ReceivedAntrag antrag = event.getAntrag();
        log.info("AUDIT received submission={} case={} destination={} service={} mimeType={} bytes={}",
                antrag.getSubmissionId(),
                antrag.getCaseId(),
                antrag.getDestinationId(),
                antrag.getServiceType().getIdentifier(),
                antrag.getDataMimeType(),
                antrag.getDataAsBytes().length);
    }
}
