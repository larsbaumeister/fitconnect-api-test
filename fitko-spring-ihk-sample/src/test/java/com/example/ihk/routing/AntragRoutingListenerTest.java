package com.example.ihk.routing;

import com.example.ihk.processstarter.ProcessStartRequest;
import com.example.ihk.processstarter.ProcessStarter;
import com.example.ihk.processstarter.ProcessStarterLookup;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No Spring context: build an {@link IncomingSubmission} test double and
 * call the listener method directly, same style as fitko-spring-sample's
 * {@code GewerbeanmeldungHandlerTest}. {@link ProcessStarterLookup}'s
 * {@code ApplicationContext} dependency is mocked here rather than started
 * for real - {@code IhkAntragRouterApplicationTests} covers the full-context
 * wiring (including {@code ProcessStarterLookup}'s startup validation).
 */
class AntragRoutingListenerTest {

    private static final String AUSBILDUNGSVERTRAG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String UNMAPPED = "urn:de:fim:leika:leistung:00000000000000";
    private static final UUID AACHEN_DESTINATION = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID HANNOVER_DESTINATION = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    // Two DISTINCT ProcessStarter implementation classes, one per tenant -
    // proves AntragRoutingListener really dispatches to a different
    // implementation class, not just a different value passed to one shared
    // instance.
    private final RecordingProcessStarterA aachenStarter = new RecordingProcessStarterA();
    private final RecordingProcessStarterB hannoverStarter = new RecordingProcessStarterB();

    private final AntragRoutingListener listener =
            new AntragRoutingListener(resolver(), tenantDirectory(), processStarterLookup());

    @Test
    void dispatchesToTheMappedProcessStarterImplementationAndAcceptsTheSubmission() {
        UUID submissionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        IncomingSubmission submission = stubSubmission(submissionId, caseId, AACHEN_DESTINATION, AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(aachenStarter.requests).singleElement().satisfies(request -> {
            assertThat(request.submission().getSubmissionId()).isEqualTo(submissionId);
            assertThat(request.submission().getCaseId()).isEqualTo(caseId);
            assertThat(request.tenant()).isEqualTo("101-aachen");
            assertThat(request.submission().getServiceType().getIdentifier()).isEqualTo(AUSBILDUNGSVERTRAG);
            // The whole point of this change: the concrete Antrag content is
            // reachable through the request, not just ids.
            assertThat(request.submission().getDataAsString()).isEqualTo("<Antrag>concrete data</Antrag>");
            assertThat(request.submission().getDataMimeType()).isEqualTo("text/xml");
        });
        assertThat(hannoverStarter.requests).isEmpty();
        verify(submission).accept();
    }

    @Test
    void leavesAnUnmappedSubmissionUnresolvedInsteadOfGuessing() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), UUID.randomUUID(), AACHEN_DESTINATION, UNMAPPED);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(aachenStarter.requests).isEmpty();
        assertThat(hannoverStarter.requests).isEmpty();
        verify(submission, never()).accept();
    }

    @Test
    void dispatchesToADifferentProcessStarterImplementationForTheSameLeistungInAnotherTenant() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), UUID.randomUUID(), HANNOVER_DESTINATION, AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(hannoverStarter.requests).singleElement()
                .extracting(ProcessStartRequest::tenant).isEqualTo("133-hannover");
        assertThat(aachenStarter.requests).isEmpty();
    }

    @Test
    void leavesAnUnconfiguredDestinationUnresolvedRatherThanGuessingItsTenant() {
        // A destination TenantDirectory has no entry for (e.g. config drift
        // between fitconnect.receiver.tenants and reality) resolves to
        // "unknown-tenant", which in turn has no antrag-routing mapping - so
        // this is just the unmapped-tenant case, not a special one.
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(aachenStarter.requests).isEmpty();
        assertThat(hannoverStarter.requests).isEmpty();
        verify(submission, never()).accept();
    }

    private static IncomingSubmission stubSubmission(UUID submissionId, UUID caseId, UUID destinationId, String leikaSchluessel) {
        IncomingSubmission submission = mock(IncomingSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getCaseId()).thenReturn(caseId);
        when(submission.getDestinationId()).thenReturn(destinationId);
        when(submission.getServiceType()).thenReturn(new PublicService("Leistung", leikaSchluessel));
        // The concrete Antrag payload a real ProcessStarter would need -
        // stubbed here to prove it survives all the way through
        // ProcessStartRequest, not just the submission/case ids.
        when(submission.getDataAsString()).thenReturn("<Antrag>concrete data</Antrag>");
        when(submission.getDataMimeType()).thenReturn("text/xml");
        return submission;
    }

    private static AntragRoutingProperties routingProperties() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessStarterByTenant(Map.of(
                "101-aachen", Map.of(AUSBILDUNGSVERTRAG, RecordingProcessStarterA.class.getName()),
                "133-hannover", Map.of(AUSBILDUNGSVERTRAG, RecordingProcessStarterB.class.getName())));
        return properties;
    }

    private static AntragProcessResolver resolver() {
        return new AntragProcessResolver(routingProperties());
    }

    private ProcessStarterLookup processStarterLookup() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(RecordingProcessStarterA.class)).thenReturn(aachenStarter);
        when(context.getBean(RecordingProcessStarterB.class)).thenReturn(hannoverStarter);
        return new ProcessStarterLookup(context, routingProperties());
    }

    private static TenantDirectory tenantDirectory() {
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
        return new TenantDirectory(properties);
    }

    private static final class RecordingProcessStarterA implements ProcessStarter {
        private final List<ProcessStartRequest> requests = new ArrayList<>();

        @Override
        public void start(ProcessStartRequest request) {
            requests.add(request);
        }
    }

    private static final class RecordingProcessStarterB implements ProcessStarter {
        private final List<ProcessStartRequest> requests = new ArrayList<>();

        @Override
        public void start(ProcessStartRequest request) {
            requests.add(request);
        }
    }
}
