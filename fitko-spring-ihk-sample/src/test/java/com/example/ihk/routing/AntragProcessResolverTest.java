package com.example.ihk.routing;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AntragProcessResolverTest {

    private static final String AUSBILDUNGSVERTRAG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String SACHKUNDEPRUEFUNG = "urn:de:fim:leika:leistung:99050035002000";
    private static final String UNMAPPED = "urn:de:fim:leika:leistung:00000000000000";

    @Test
    void resolvesTheSameLeistungToDifferentProcessesPerTenant() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessByTenant(Map.of(
                "101-aachen", Map.of(AUSBILDUNGSVERTRAG, "ausbildungsvertrag-process-aachen"),
                "133-hannover", Map.of(AUSBILDUNGSVERTRAG, "ausbildungsvertrag-process-hannover")));

        AntragProcessResolver resolver = new AntragProcessResolver(properties);

        assertThat(resolver.resolveProcessKey("101-aachen", AUSBILDUNGSVERTRAG)).contains("ausbildungsvertrag-process-aachen");
        assertThat(resolver.resolveProcessKey("133-hannover", AUSBILDUNGSVERTRAG)).contains("ausbildungsvertrag-process-hannover");
    }

    @Test
    void fallsBackToTheDefaultProcessKeyForAnUnmappedLeistung() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessByTenant(Map.of("101-aachen", Map.of(AUSBILDUNGSVERTRAG, "ausbildungsvertrag-process-aachen")));
        properties.setDefaultProcessKey("manual-review-process");

        AntragProcessResolver resolver = new AntragProcessResolver(properties);

        assertThat(resolver.resolveProcessKey("101-aachen", UNMAPPED)).contains("manual-review-process");
        assertThat(resolver.resolveProcessKey("133-hannover", AUSBILDUNGSVERTRAG)).contains("manual-review-process");
    }

    @Test
    void isEmptyForAnUnmappedTenantOrLeistungWithNoDefaultConfigured() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessByTenant(Map.of("101-aachen", Map.of(AUSBILDUNGSVERTRAG, "ausbildungsvertrag-process-aachen")));

        AntragProcessResolver resolver = new AntragProcessResolver(properties);

        assertThat(resolver.resolveProcessKey("101-aachen", UNMAPPED)).isEqualTo(Optional.empty());
        assertThat(resolver.resolveProcessKey("133-hannover", AUSBILDUNGSVERTRAG)).isEqualTo(Optional.empty());
    }
}
