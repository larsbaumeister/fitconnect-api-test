package com.example.ihk.routing;

import com.gfi.ozg.fitko.spring.FitConnectProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which configured tenant (e.g. {@code "101-aachen"}) a destination belongs
 * to, given its id.
 *
 * <p><b>Gap this works around:</b> fitko-spring's receive event ({@code
 * IncomingSubmission}) only ever carries the raw destination UUID ({@code
 * getDestinationId()}) - the tenant/destination names chosen in
 * {@code application.yaml} are configuration-time-only labels, never
 * propagated to the runtime event, so a multi-tenant consumer has to rebuild
 * this lookup itself. Done here without any duplicate config: {@link
 * FitConnectProperties} is already a normal public Spring bean ({@code
 * @EnableConfigurationProperties} on {@code FitConnectAutoConfiguration}),
 * so this just re-reads the same {@code fitconnect.receiver.tenants} tree
 * fitko-spring itself parsed at startup.
 */
@Component
public class TenantDirectory {

    private final Map<UUID, String> tenantByDestinationId;

    public TenantDirectory(FitConnectProperties properties) {
        Map<UUID, String> byDestination = new HashMap<>();
        properties.getReceiver().getTenants().forEach((tenantName, tenant) ->
                tenant.getDestinations().values().forEach(destination ->
                        byDestination.put(destination.getId(), tenantName)));
        this.tenantByDestinationId = Map.copyOf(byDestination);
    }

    public Optional<String> tenantOf(UUID destinationId) {
        return Optional.ofNullable(tenantByDestinationId.get(destinationId));
    }
}
