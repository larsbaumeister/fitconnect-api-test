package com.gfi.ozg.ficon.routing;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AntragProcessResolverTest {

    private static final String AUSBILDUNGSVERTRAG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String SACHKUNDEPRUEFUNG = "urn:de:fim:leika:leistung:99050035002000";
    private static final String UNMAPPED = "urn:de:fim:leika:leistung:00000000000000";
    private static final String AACHEN_STARTER = "com.gfi.ozg.ficon.processstarter.impl.LoggingProcessStarter";
    private static final String HANNOVER_STARTER = "com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter";

    @Test
    void resolvesTheSameLeistungToDifferentProcessStarterClassesPerTenant() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessStarterByTenant(Map.of(
                "101-aachen", Map.of(AUSBILDUNGSVERTRAG, AACHEN_STARTER),
                "133-hannover", Map.of(AUSBILDUNGSVERTRAG, HANNOVER_STARTER)));

        AntragProcessResolver resolver = new AntragProcessResolver(properties);

        assertThat(resolver.resolveProcessStarterClassName("101-aachen", AUSBILDUNGSVERTRAG)).contains(AACHEN_STARTER);
        assertThat(resolver.resolveProcessStarterClassName("133-hannover", AUSBILDUNGSVERTRAG)).contains(HANNOVER_STARTER);
    }

    @Test
    void fallsBackToTheDefaultProcessStarterClassForAnUnmappedLeistung() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessStarterByTenant(Map.of("101-aachen", Map.of(AUSBILDUNGSVERTRAG, AACHEN_STARTER)));
        properties.setDefaultProcessStarterClass("com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter");

        AntragProcessResolver resolver = new AntragProcessResolver(properties);

        assertThat(resolver.resolveProcessStarterClassName("101-aachen", UNMAPPED))
                .contains("com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter");
        assertThat(resolver.resolveProcessStarterClassName("133-hannover", AUSBILDUNGSVERTRAG))
                .contains("com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter");
        assertThat(resolver.resolveProcessStarterClassName("101-aachen", SACHKUNDEPRUEFUNG))
                .contains("com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter");
    }

    @Test
    void isEmptyForAnUnmappedTenantOrLeistungWithNoDefaultConfigured() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessStarterByTenant(Map.of("101-aachen", Map.of(AUSBILDUNGSVERTRAG, AACHEN_STARTER)));

        AntragProcessResolver resolver = new AntragProcessResolver(properties);

        assertThat(resolver.resolveProcessStarterClassName("101-aachen", UNMAPPED)).isEqualTo(Optional.empty());
        assertThat(resolver.resolveProcessStarterClassName("133-hannover", AUSBILDUNGSVERTRAG)).isEqualTo(Optional.empty());
    }
}
