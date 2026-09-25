package com.gfi.ozg.ficon;

import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.ProcessStarterLookup;
import com.gfi.ozg.ficon.processstarter.impl.LoggingProcessStarter;
import com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter;
import com.gfi.ozg.ficon.routing.AntragRoutingListener;
import com.gfi.ozg.ficon.routing.TenantDirectory;
import com.gfi.ozg.ficon.support.IhkApplicationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full context smoke test: both demo tenants ({@code 101-aachen}, {@code
 * 133-hannover}) wired with real (throwaway) keys, and the SDK's {@code
 * SubscriberClient} mocked out (see {@link IhkApplicationTestSupport}) so nothing touches the network. Proves the
 * whole starter plus this project's own routing beans ({@code
 * AntragRoutingProperties}, {@link TenantDirectory}, {@code
 * AntragProcessResolver}, {@link AntragRoutingListener}, {@link
 * ProcessStarterLookup} and both {@link ProcessStarter} implementations)
 * wire up together, the way they would when actually deployed - including
 * {@link ProcessStarterLookup}'s startup-time validation of every class name
 * referenced in {@code application.yaml}'s {@code antrag-routing.*}
 * (the context simply wouldn't start if that failed).
 */
class IhkAntragRouterApplicationTests extends IhkApplicationTestSupport {

    @Autowired
    AntragRoutingListener antragRoutingListener;

    @Autowired
    TenantDirectory tenantDirectory;

    @Autowired
    ProcessStarterLookup processStarterLookup;

    @Autowired
    NoopProcessStarter noopProcessStarter;

    @Autowired
    LoggingProcessStarter loggingProcessStarter;

    @Test
    void contextLoadsAndWiresTheRoutingBeans() {
        assertThat(antragRoutingListener).isNotNull();
        assertThat(tenantDirectory).isNotNull();
        // Both implementations coexist as beans - which one runs for a given
        // Antrag is a config-time class-name lookup, not a single injected
        // ProcessStarter (see ProcessStarterLookup).
        assertThat(noopProcessStarter).isNotNull();
        assertThat(loggingProcessStarter).isNotNull();
    }

    @Test
    void lookupResolvesEachConfiguredClassNameToItsMatchingBean() {
        ProcessStarter resolvedNoop = processStarterLookup.resolve(NoopProcessStarter.class.getName());
        ProcessStarter resolvedLogging = processStarterLookup.resolve(LoggingProcessStarter.class.getName());

        assertThat(resolvedNoop).isSameAs(noopProcessStarter);
        assertThat(resolvedLogging).isSameAs(loggingProcessStarter);
    }
}
