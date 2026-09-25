package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.inbox.SubmissionInbox;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.gfi.ozg.ficon.processstarter.ProcessStarterResolver;
import com.gfi.ozg.ficon.processstarter.ProcessStarterRoutingProperties;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * No Spring context: build an {@link IncomingSubmission} test double and
 * call the listener method directly, same style as fitko-spring-sample's
 * {@code GewerbeanmeldungHandlerTest}. {@link SubmissionInbox} is mocked -
 * {@code InboxIntegrationTest} covers storing and dispatching for real.
 */
class AntragReceiveListenerTest {

    private static final String AUSBILDUNGSVERTRAG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String UNMAPPED = "urn:de:fim:leika:leistung:00000000000000";
    private static final UUID AACHEN_DESTINATION = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");

    private final SubmissionInbox inbox = mock(SubmissionInbox.class);
    private final AntragReceiveListener listener = new AntragReceiveListener(resolver(), tenantDirectory(), inbox);

    @Test
    void storesTheSubmissionForItsTenantBeforeAcceptingIt() {
        IncomingSubmission submission = stubSubmission(AACHEN_DESTINATION, AUSBILDUNGSVERTRAG);
        when(inbox.store(submission, "101-aachen")).thenReturn(true);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        var order = inOrder(inbox, submission);
        order.verify(inbox).store(submission, "101-aachen");
        order.verify(submission).accept();
    }

    @Test
    void acceptsARedeliveredSubmissionWithoutStoringItAgain() {
        // The accept() after an earlier successful store failed - FIT-Connect
        // offers the submission again; it must still end up accepted.
        IncomingSubmission submission = stubSubmission(AACHEN_DESTINATION, AUSBILDUNGSVERTRAG);
        when(inbox.store(submission, "101-aachen")).thenReturn(false);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        verify(submission).accept();
    }

    @Test
    void doesNotAcceptASubmissionThatCouldNotBeStored() {
        IncomingSubmission submission = stubSubmission(AACHEN_DESTINATION, AUSBILDUNGSVERTRAG);
        when(inbox.store(submission, "101-aachen")).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.onAntrag(new SubmissionReceivedEvent(this, submission)))
                .hasMessage("database down");

        verify(submission, never()).accept();
    }

    @Test
    void leavesAnUnmappedSubmissionUnresolvedInsteadOfGuessing() {
        IncomingSubmission submission = stubSubmission(AACHEN_DESTINATION, UNMAPPED);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        verifyNoInteractions(inbox);
        verify(submission, never()).accept();
    }

    @Test
    void leavesAnUnconfiguredDestinationUnresolvedRatherThanGuessingItsTenant() {
        // A destination TenantDirectory has no entry for (e.g. config drift
        // between fitconnect.receiver.tenants and reality) resolves to
        // "unknown-tenant", which in turn has no antrag-routing mapping - so
        // this is just the unmapped-tenant case, not a special one.
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        verify(inbox, never()).store(any(), any());
        verify(submission, never()).accept();
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
