package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.inbox.SubmissionInbox;
import com.gfi.ozg.ficon.processstarter.ProcessStarterLookup;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.gfi.ozg.ficon.processstarter.ProcessStarterResolver;
import com.gfi.ozg.ficon.processstarter.ProcessStarterRoutingProperties;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * No Spring context: build an {@link IncomingSubmission} test double and
 * call the listener method directly, same style as fitko-spring-sample's
 * {@code GewerbeanmeldungHandlerTest} - for the cases decided before the
 * inbox is involved at all. {@code AntragReceiveIntegrationTest} covers the
 * whole store/start/accept/reject flow against a real database.
 */
class AntragReceiveListenerTest {

    private static final String AUSBILDUNGSVERTRAG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String UNMAPPED = "urn:de:fim:leika:leistung:00000000000000";
    private static final UUID AACHEN_DESTINATION = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");

    private final SubmissionInbox inbox = mock(SubmissionInbox.class);
    private final ProcessStarterLookup processStarters = mock(ProcessStarterLookup.class);
    private final AntragReceiveListener listener =
            new AntragReceiveListener(resolver(), tenantDirectory(), processStarters, inbox);

    @Test
    void leavesAnUnmappedSubmissionUnresolvedInsteadOfGuessing() {
        IncomingSubmission submission = stubSubmission(AACHEN_DESTINATION, UNMAPPED);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        verifyNoInteractions(inbox, processStarters);
        verify(submission, never()).accept();
        verify(submission, never()).reject(anyList());
    }

    @Test
    void leavesAnUnconfiguredDestinationUnresolvedRatherThanGuessingItsTenant() {
        // A destination TenantDirectory has no entry for (e.g. config drift
        // between fitconnect.receiver.tenants and reality) resolves to
        // "unknown-tenant", which in turn has no antrag-routing mapping - so
        // this is just the unmapped-tenant case, not a special one.
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        verifyNoInteractions(inbox, processStarters);
        verify(submission, never()).accept();
        verify(submission, never()).reject(anyList());
    }

    private static IncomingSubmission stubSubmission(UUID destinationId, String leikaSchluessel) {
        IncomingSubmission submission = mock(IncomingSubmission.class);
        when(submission.getSubmissionId()).thenReturn(UUID.randomUUID());
        when(submission.getDestinationId()).thenReturn(destinationId);
        when(submission.getServiceType()).thenReturn(new PublicService("Leistung", leikaSchluessel));
        return submission;
    }

    private static ProcessStarterResolver resolver() {
        ProcessStarterRoutingProperties properties = new ProcessStarterRoutingProperties();
        properties.setProcessStarterByTenant(Map.of(
                "101-aachen", Map.of(AUSBILDUNGSVERTRAG, "com.gfi.ozg.ficon.SomeProcessStarter")));
        return new ProcessStarterResolver(properties);
    }

    private static TenantDirectory tenantDirectory() {
        FitConnectProperties.Receiver.Destination aachen = new FitConnectProperties.Receiver.Destination();
        aachen.setId(AACHEN_DESTINATION);
        FitConnectProperties.Receiver.Tenant aachenTenant = new FitConnectProperties.Receiver.Tenant();
        aachenTenant.setDestinations(Map.of("antragseingang", aachen));

        FitConnectProperties properties = new FitConnectProperties();
        properties.getReceiver().setTenants(Map.of("101-aachen", aachenTenant));
        return new TenantDirectory(properties);
    }
}
