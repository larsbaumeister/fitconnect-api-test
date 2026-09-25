package com.gfi.ozg.ficon.routing;

import com.gfi.ozg.ficon.inbox.AntragDispatcher;
import com.gfi.ozg.ficon.inbox.SubmissionInbox;
import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Receives every incoming Antrag and moves it from the delivery service into
 * this application's inbox: store it ({@link SubmissionInbox}), then {@link
 * IncomingSubmission#accept()}. Starting the actual process is <em>not</em>
 * done here - {@link AntragDispatcher} picks stored submissions up
 * asynchronously and hands them to their {@link ProcessStarter}.
 *
 * <p>No {@code serviceIds} filter on {@link SubmissionEventListener} - unlike
 * fitko-spring-sample's per-Leistung listeners, this one has to see every
 * submission to make the routing decision at all.
 *
 * <p><b>Store first, then accept.</b> Accepting deletes the submission from
 * the delivery service, so it must be durably stored before - otherwise a
 * crash in between would lose the Antrag. The other order's failure mode is
 * harmless: stored, but {@code accept()} fails (network) - it stays on the
 * delivery service, is delivered again, {@link SubmissionInbox#store} finds
 * it already stored, and it is only accepted this time, not stored or
 * processed twice.
 *
 * <p>If storing fails, the exception propagates: fitko-spring leaves the
 * submission on the delivery service and retries it next poll cycle.
 */
@Component
public class AntragRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(AntragRoutingListener.class);

    private final AntragProcessResolver resolver;
    private final TenantDirectory tenants;
    private final SubmissionInbox inbox;

    public AntragRoutingListener(AntragProcessResolver resolver, TenantDirectory tenants, SubmissionInbox inbox) {
        this.resolver = resolver;
        this.tenants = tenants;
        this.inbox = inbox;
    }

    @SubmissionEventListener
    public void onAntrag(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();
        String leikaSchluessel = submission.getServiceType().getIdentifier();
        String tenant = tenants.tenantOf(submission.getDestinationId()).orElse("unknown-tenant");

        if (resolver.resolveProcessStarterClassName(tenant, leikaSchluessel).isEmpty()) {
            // No tenant-specific mapping and no default-process-starter-class:
            // don't accept what nothing here could process. Leave it on the
            // delivery service (LEAVE, the safe default-outcome) rather than
            // guess an implementation or silently drop it - see
            // identity-routing-trust.md's "don't guess" fallback guidance.
            // Shows up as a stuck submission in fitconnect.receive.*
            // metrics/logs until routing config catches up.
            log.warn("No ProcessStarter mapped for tenant {} / Leistung {} (submission {}) - leaving unresolved",
                    tenant, leikaSchluessel, submission.getSubmissionId());
            return;
        }

        if (inbox.store(submission, tenant)) {
            log.info("Stored submission {} (tenant {}, Leistung {}) in the inbox",
                    submission.getSubmissionId(), tenant, leikaSchluessel);
        } else {
            log.info("Submission {} is already in the inbox - re-delivery (e.g. an earlier accept() failed), "
                    + "accepting without storing it again", submission.getSubmissionId());
        }

        submission.accept();
    }
}
