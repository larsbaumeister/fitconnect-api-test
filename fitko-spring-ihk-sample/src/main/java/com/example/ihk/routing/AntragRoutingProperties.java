package com.example.ihk.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-driven mapping from (tenant, LeiKa-Schluessel) to the Camunda
 * process definition key that should be started - per-tenant, not global:
 * the same Leistung (e.g. {@code urn:de:fim:leika:leistung:99050035001000})
 * can start a different process for {@code 101-aachen} than for {@code
 * 133-hannover}, since each regional tenant may run its own Fachverfahren
 * for the same nationally standardized Leistung. {@code
 * IncomingSubmission.getServiceType().getIdentifier()} supplies the
 * Leistung side of the key; the tenant side comes from {@link
 * TenantDirectory}.
 *
 * <p>Both nesting levels are {@code Map}-typed and merge cleanly across
 * imported property files the same way {@code fitconnect.receiver.tenants}
 * does - see fitko-spring's configuration.md, "Splitting configuration
 * across files" - so this can grow to all 79 tenants as one file per tenant
 * without becoming unmanageable.
 */
@ConfigurationProperties(prefix = "antrag-routing")
public class AntragRoutingProperties {

    /** {@code tenant -> (leikaSchluessel -> processKey)}. */
    private Map<String, Map<String, String>> processByTenant = new LinkedHashMap<>();

    /**
     * Process to start when the incoming (tenant, Leistung) pair has no
     * entry in {@link #processByTenant} - e.g. a manual-review process. Left
     * unset, an unmapped combination is logged and no process is started
     * (see {@code AntragRoutingListener}) rather than guessed.
     */
    private String defaultProcessKey;

    public Map<String, Map<String, String>> getProcessByTenant() {
        return processByTenant;
    }

    public void setProcessByTenant(Map<String, Map<String, String>> processByTenant) {
        this.processByTenant = processByTenant;
    }

    public String getDefaultProcessKey() {
        return defaultProcessKey;
    }

    public void setDefaultProcessKey(String defaultProcessKey) {
        this.defaultProcessKey = defaultProcessKey;
    }
}
