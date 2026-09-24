package com.example.ihk.routing;

import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import org.junit.jupiter.api.Test;

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
 * {@code GewerbeanmeldungHandlerTest}.
 */
class AntragRoutingListenerTest {

    private static final String AUSBILDUNGSVERTRAG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String UNMAPPED = "urn:de:fim:leika:leistung:00000000000000";
    private static final UUID AACHEN_DESTINATION = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID HANNOVER_DESTINATION = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    private final RecordingProcessStarter processStarter = new RecordingProcessStarter();
    private final AntragRoutingListener listener = new AntragRoutingListener(resolver(), tenantDirectory(), processStarter);

    @Test
    void startsTheMappedProcessAndAcceptsTheSubmission() {
        UUID submissionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        IncomingSubmission submission = stubSubmission(submissionId, caseId, AACHEN_DESTINATION, AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(processStarter.requests).singleElement().satisfies(request -> {
            assertThat(request.processKey()).isEqualTo("ausbildungsvertrag-process-aachen");
            assertThat(request.submissionId()).isEqualTo(submissionId);
            assertThat(request.caseId()).isEqualTo(caseId);
            assertThat(request.tenant()).isEqualTo("101-aachen");
            assertThat(request.leikaSchluessel()).isEqualTo(AUSBILDUNGSVERTRAG);
        });
        verify(submission).accept();
    }

    @Test
    void leavesAnUnmappedSubmissionUnresolvedInsteadOfGuessing() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), UUID.randomUUID(), AACHEN_DESTINATION, UNMAPPED);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(processStarter.requests).isEmpty();
        verify(submission, never()).accept();
    }

    @Test
    void startsADifferentProcessForTheSameLeistungInAnotherTenant() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), UUID.randomUUID(), HANNOVER_DESTINATION, AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(processStarter.requests).singleElement().satisfies(request -> {
            assertThat(request.tenant()).isEqualTo("133-hannover");
            assertThat(request.processKey()).isEqualTo("ausbildungsvertrag-process-hannover");
        });
    }

    @Test
    void leavesAnUnconfiguredDestinationUnresolvedRatherThanGuessingItsTenant() {
        // A destination TenantDirectory has no entry for (e.g. config drift
        // between fitconnect.receiver.tenants and reality) resolves to
        // "unknown-tenant", which in turn has no antrag-routing mapping - so
        // this is just the unmapped-tenant case, not a special one.
        IncomingSubmission submission = stubSubmission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AUSBILDUNGSVERTRAG);

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        assertThat(processStarter.requests).isEmpty();
        verify(submission, never()).accept();
    }

    private static IncomingSubmission stubSubmission(UUID submissionId, UUID caseId, UUID destinationId, String leikaSchluessel) {
        IncomingSubmission submission = mock(IncomingSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getCaseId()).thenReturn(caseId);
        when(submission.getDestinationId()).thenReturn(destinationId);
        when(submission.getServiceType()).thenReturn(new PublicService("Leistung", leikaSchluessel));
        return submission;
    }

    private static AntragProcessResolver resolver() {
        AntragRoutingProperties properties = new AntragRoutingProperties();
        properties.setProcessByTenant(Map.of(
                "101-aachen", Map.of(AUSBILDUNGSVERTRAG, "ausbildungsvertrag-process-aachen"),
                "133-hannover", Map.of(AUSBILDUNGSVERTRAG, "ausbildungsvertrag-process-hannover")));
        return new AntragProcessResolver(properties);
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

    private static final class RecordingProcessStarter implements ProcessStarter {
        private final List<ProcessStartRequest> requests = new ArrayList<>();

        @Override
        public void start(ProcessStartRequest request) {
            requests.add(request);
        }
    }
}
