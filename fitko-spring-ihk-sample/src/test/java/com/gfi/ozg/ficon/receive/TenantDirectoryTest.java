package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.fitko.spring.FitConnectProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantDirectoryTest {

    private static final UUID AACHEN_DESTINATION = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID HANNOVER_DESTINATION = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    @Test
    void resolvesTheTenantOwningEachConfiguredDestination() {
        TenantDirectory directory = new TenantDirectory(twoTenantProperties());

        assertThat(directory.tenantOf(AACHEN_DESTINATION)).contains("101-aachen");
        assertThat(directory.tenantOf(HANNOVER_DESTINATION)).contains("133-hannover");
    }

    @Test
    void isEmptyForAnUnconfiguredDestination() {
        TenantDirectory directory = new TenantDirectory(twoTenantProperties());

        assertThat(directory.tenantOf(UUID.randomUUID())).isEmpty();
    }

    private static FitConnectProperties twoTenantProperties() {
        FitConnectProperties.Receiver.Destination aachen = new FitConnectProperties.Receiver.Destination();
        aachen.setId(AACHEN_DESTINATION);
        FitConnectProperties.Receiver.Tenant aachenTenant = new FitConnectProperties.Receiver.Tenant();
        aachenTenant.setDestinations(Map.of("antragseingang", aachen));

        FitConnectProperties.Receiver.Destination hannover = new FitConnectProperties.Receiver.Destination();
        hannover.setId(HANNOVER_DESTINATION);
        FitConnectProperties.Receiver.Tenant hannoverTenant = new FitConnectProperties.Receiver.Tenant();
        hannoverTenant.setDestinations(Map.of("antragseingang", hannover));

        FitConnectProperties properties = new FitConnectProperties();
        properties.getReceiver().setTenants(Map.of(
                "101-aachen", aachenTenant,
                "133-hannover", hannoverTenant));
        return properties;
    }
}
