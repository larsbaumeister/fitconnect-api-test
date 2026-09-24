package com.example.ihk.routing;

import com.example.ihk.processstarter.ProcessStarter;
import com.example.ihk.processstarter.ProcessStarterLookup;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * The routing decision itself: which {@link ProcessStarter} implementation
 * (by fully-qualified class name) should handle a given tenant + LeiKa-
 * Schluessel. A pure config lookup into {@link AntragRoutingProperties} - no
 * DMN engine, no per-submission Java branching to maintain, an ops/business
 * change is a config change. Deliberately has no Spring-context/bean-lookup
 * knowledge of its own - {@link ProcessStarterLookup} turns the class name
 * this returns into the actual bean.
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
     * @return the fully-qualified {@link ProcessStarter} implementation class name to dispatch to, or
     *     empty when this (tenant, Leistung) pair has neither a configured mapping nor a
     *     {@code antrag-routing.default-process-starter-class}
     */
    public Optional<String> resolveProcessStarterClassName(String tenant, String leikaSchluessel) {
        Map<String, String> tenantMappings = properties.getProcessStarterByTenant().get(tenant);
        String mapped = tenantMappings == null ? null : tenantMappings.get(leikaSchluessel);
        if (mapped != null) {
            return Optional.of(mapped);
        }
        return Optional.ofNullable(properties.getDefaultProcessStarterClass());
    }
}
