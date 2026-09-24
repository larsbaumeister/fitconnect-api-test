package com.example.ihk.routing;

import com.example.ihk.processstarter.ProcessStarter;
import com.example.ihk.processstarter.ProcessStartRequest;
import com.example.ihk.processstarter.ProcessStarterLookup;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Ties the pieces together for every incoming Antrag: resolve which tenant
 * received it, then which {@link ProcessStarter} implementation that tenant
 * uses for its Leistung ({@link AntragProcessResolver}), then resolve that
 * class name to the actual bean ({@link ProcessStarterLookup}) and hand off
 * to it.
 *
 * <p>No {@code serviceIds} filter on {@link SubmissionEventListener} - unlike
 * fitko-spring-sample's per-Leistung listeners, this one has to see every
 * submission to make the routing decision at all.
 *
 * <p><b>Idempotency:</b> delivery is at-least-once, no de-duplication (see
 * fitko-spring's architecture.md, "Delivery semantics") - a replay between
 * {@link IncomingSubmission#accept()} and the delivery service's own delete
 * taking effect is possible. Every {@link ProcessStarter} implementation
 * must itself be idempotent for the same {@code submissionId} (e.g. a real
 * Camunda-backed one should key the process instance's business key on it
 * and no-op on a duplicate).
 *
 * <p><b>Who calls {@code accept()}:</b> this listener does, once {@link
 * ProcessStarter#start} returns without throwing - see {@link
 * ProcessStartRequest}'s javadoc for why implementations must not call
 * {@link IncomingSubmission#accept()}/{@link IncomingSubmission#reject}
 * themselves even though the whole submission is now in their hands.
 */
@Component
public class AntragRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(AntragRoutingListener.class);

    private final AntragProcessResolver resolver;
    private final TenantDirectory tenants;
    private final ProcessStarterLookup processStarters;

    public AntragRoutingListener(AntragProcessResolver resolver, TenantDirectory tenants, ProcessStarterLookup processStarters) {
        this.resolver = resolver;
        this.tenants = tenants;
        this.processStarters = processStarters;
    }

    @SubmissionEventListener
    public void onAntrag(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();
        String leikaSchluessel = submission.getServiceType().getIdentifier();
        String tenant = tenants.tenantOf(submission.getDestinationId()).orElse("unknown-tenant");

        Optional<String> processStarterClassName = resolver.resolveProcessStarterClassName(tenant, leikaSchluessel);
        if (processStarterClassName.isEmpty()) {
            // No tenant-specific mapping and no default-process-starter-class:
            // leave it on the delivery service (LEAVE, the safe
            // default-outcome) rather than guess an implementation or
            // silently drop it - see identity-routing-trust.md's "don't
            // guess" fallback guidance. Shows up as a stuck submission in
            // fitconnect.receive.* metrics/logs until routing config catches
            // up.
            log.warn("No ProcessStarter mapped for tenant {} / Leistung {} (submission {}) - leaving unresolved",
                    tenant, leikaSchluessel, submission.getSubmissionId());
            return;
        }

        ProcessStarter processStarter = processStarters.resolve(processStarterClassName.get());
        processStarter.start(new ProcessStartRequest(submission, tenant));

        submission.accept();
    }
}
