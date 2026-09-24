package com.example.ihk.routing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * The routing decision itself: which process definition key to start for a
 * given tenant + LeiKa-Schluessel. A pure config lookup into {@link
 * AntragRoutingProperties} - no DMN engine, no per-submission Java branching
 * to maintain, an ops/business change is a config change.
 */
@Component
public class AntragProcessResolver {

    private final AntragRoutingProperties properties;

    public AntragProcessResolver(AntragRoutingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param tenant the tenant that received the submission, e.g. {@code "101-aachen"} (see {@link TenantDirectory})
     * @param leikaSchluessel the Leistung's URN, e.g. {@code IncomingSubmission.getServiceType().getIdentifier()}
     * @return the process definition key to start, or empty when this (tenant, Leistung) pair has
     *     neither a configured mapping nor a {@code antrag-routing.default-process-key}
     */
    public Optional<String> resolveProcessKey(String tenant, String leikaSchluessel) {
        Map<String, String> tenantMappings = properties.getProcessByTenant().get(tenant);
        String mapped = tenantMappings == null ? null : tenantMappings.get(leikaSchluessel);
        if (mapped != null) {
            return Optional.of(mapped);
        }
        return Optional.ofNullable(properties.getDefaultProcessKey());
    }
}
