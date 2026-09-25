package com.gfi.ozg.ficon.processstarter;

import com.gfi.ozg.ficon.receive.TenantDirectory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-driven mapping from (tenant, LeiKa-Schluessel) to the {@link
 * ProcessStarter} implementation that should handle it - by fully-qualified
 * class name, not an arbitrary process-key string. The same Leistung (e.g.
 * {@code urn:de:fim:leika:leistung:99050035001000}) can be wired to a
 * different implementation class for {@code 101-aachen} than for {@code
 * 133-hannover}, since each regional tenant may run its own Fachverfahren -
 * possibly through entirely different code, not just a different process
 * definition of the same engine - for the same nationally standardized
 * Leistung. {@code IncomingSubmission.getServiceType().getIdentifier()}
 * supplies the Leistung side of the key; the tenant side comes from {@link
 * TenantDirectory}. See {@link ProcessStarterLookup} for how a configured
 * class name becomes the actual Spring-managed bean that runs.
 *
 * <p>Both nesting levels are {@code Map}-typed and merge cleanly across
 * imported property files the same way {@code fitconnect.receiver.tenants}
 * does - see fitko-spring's configuration.md, "Splitting configuration
 * across files" - so this can grow to all 79 tenants as one file per tenant
 * without becoming unmanageable.
 */
@ConfigurationProperties(prefix = "antrag-routing")
public class ProcessStarterRoutingProperties {

    /** {@code tenant -> (leikaSchluessel -> fully-qualified ProcessStarter implementation class name)}. */
    private Map<String, Map<String, String>> processStarterByTenant = new LinkedHashMap<>();

    /**
     * Fully-qualified {@link ProcessStarter} implementation class to use
     * when the incoming (tenant, Leistung) pair has no entry in {@link
     * #processStarterByTenant} - e.g. {@code NoopProcessStarter}, or a
     * manual-review implementation. Left unset, an unmapped combination is
     * logged and no process is started (see {@code AntragReceiveListener})
     * rather than guessed.
     */
    private String defaultProcessStarterClass;

    public Map<String, Map<String, String>> getProcessStarterByTenant() {
        return processStarterByTenant;
    }

    public void setProcessStarterByTenant(Map<String, Map<String, String>> processStarterByTenant) {
        this.processStarterByTenant = processStarterByTenant;
    }

    public String getDefaultProcessStarterClass() {
        return defaultProcessStarterClass;
    }

    public void setDefaultProcessStarterClass(String defaultProcessStarterClass) {
        this.defaultProcessStarterClass = defaultProcessStarterClass;
    }
}
