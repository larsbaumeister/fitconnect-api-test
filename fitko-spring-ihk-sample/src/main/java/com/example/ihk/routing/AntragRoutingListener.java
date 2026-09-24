package com.example.ihk.routing;

import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Ties the pieces together for every incoming Antrag: resolve which tenant
 * received it, then which process that tenant runs for its Leistung, then
 * hand off to {@link ProcessStarter}.
 *
 * <p>No {@code serviceIds} filter on {@link SubmissionEventListener} - unlike
 * fitko-spring-sample's per-Leistung listeners, this one has to see every
 * submission to make the routing decision at all.
 *
 * <p><b>Idempotency:</b> delivery is at-least-once, no de-duplication (see
 * fitko-spring's architecture.md, "Delivery semantics") - a replay between
 * {@link IncomingSubmission#accept()} and the delivery service's own delete
 * taking effect is possible. {@link ProcessStarter#start} must itself be
 * idempotent for the same {@code submissionId} (e.g. a real Camunda-backed
 * implementation should key the process instance's business key on it and
 * no-op on a duplicate).
 */
@Component
public class AntragRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(AntragRoutingListener.class);

    private final AntragProcessResolver resolver;
    private final TenantDirectory tenants;
    private final ProcessStarter processStarter;

    public AntragRoutingListener(AntragProcessResolver resolver, TenantDirectory tenants, ProcessStarter processStarter) {
        this.resolver = resolver;
        this.tenants = tenants;
        this.processStarter = processStarter;
    }

    @SubmissionEventListener
    public void onAntrag(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();
        String leikaSchluessel = submission.getServiceType().getIdentifier();
        String tenant = tenants.tenantOf(submission.getDestinationId()).orElse("unknown-tenant");

        Optional<String> processKey = resolver.resolveProcessKey(tenant, leikaSchluessel);
        if (processKey.isEmpty()) {
            // No tenant-specific mapping and no default-process-key: leave it
            // on the delivery service (LEAVE, the safe default-outcome)
            // rather than guess a process or silently drop it - see
            // identity-routing-trust.md's "don't guess" fallback guidance.
            // Shows up as a stuck submission in fitconnect.receive.*
            // metrics/logs until routing config catches up.
            log.warn("No process mapped for tenant {} / Leistung {} (submission {}) - leaving unresolved",
                    tenant, leikaSchluessel, submission.getSubmissionId());
            return;
        }

        processStarter.start(new ProcessStartRequest(
                processKey.get(),
                submission.getSubmissionId(),
                submission.getCaseId(),
                tenant,
                leikaSchluessel,
                Map.of(
                        "tenant", tenant,
                        "leikaSchluessel", leikaSchluessel,
                        "destinationId", submission.getDestinationId().toString())));

        submission.accept();
    }
}
