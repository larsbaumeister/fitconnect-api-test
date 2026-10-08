package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.inbox.InboxStatus;
import com.gfi.ozg.ficon.inbox.InboxSubmission;
import com.gfi.ozg.ficon.inbox.InboxSubmissionRepository;
import com.gfi.ozg.ficon.processstarter.ProcessStartRejectedException;
import com.gfi.ozg.ficon.processstarter.ProcessStartRequest;
import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import com.gfi.ozg.ficon.support.IhkApplicationTestSupport;
import com.gfi.ozg.ficon.support.TestJwkKeys;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import dev.fitko.fitconnect.api.domain.model.metadata.v2.MetadataV2;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AntragReceiveListener} end to end against H2: store, start the
 * process, accept/reject - and above all what a re-delivery does after each
 * possible failure. A delivery is simulated by calling the listener with an
 * {@link IncomingSubmission} test double; a re-delivery is a fresh double
 * with the same submission id, exactly what fitko-spring hands over on the
 * next poll cycle. The Leistung used here has no tenant mapping in {@code
 * application.yaml}, so it goes to {@code default-process-starter-class} -
 * overridden to {@link ControllableProcessStarter}.
 */
@TestPropertySource(properties =
        "antrag-routing.default-process-starter-class=com.gfi.ozg.ficon.receive.AntragReceiveIntegrationTest$ControllableProcessStarter")
@Import(AntragReceiveIntegrationTest.StarterConfig.class)
class AntragReceiveIntegrationTest extends IhkApplicationTestSupport {

    private static final String LEISTUNG = "urn:de:fim:leika:leistung:99050035009000";

    @Autowired
    AntragReceiveListener listener;

    @Autowired
    InboxSubmissionRepository repository;

    @Autowired
    ControllableProcessStarter starter;

    private final JWK replyKey = TestJwkKeys.encryptionPublicKey();

    @BeforeEach
    void cleanInbox() {
        repository.deleteAll();
        starter.reset();
    }

    @Test
    void startsTheProcessThenAcceptsAndRecordsBoth() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID());

        deliver(submission);

        verify(submission).accept();
        // Attachments reach the ProcessStarter straight from the event...
        assertThat(starter.attachmentFileNames).containsExactly(List.of("nachweis.pdf"));

        InboxSubmission stored = stored(submission);
        assertThat(stored.getStatus()).isEqualTo(InboxStatus.ACCEPTED);
        assertThat(stored.getProcessStarterClass()).contains(ControllableProcessStarter.class.getName());
        assertThat(stored.getProcessDefinition()).contains("ausbildungsvertrag-eintragung");
        assertThat(stored.getProcessInstanceId()).contains("instance-" + submission.getSubmissionId());
        assertThat(stored.getProcessStartedAt()).isPresent();
        assertThat(stored.getResolvedAt()).isPresent();
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getRejectionReason()).isEmpty();
    }

    @Test
    void storesWhatIsNeededToWorkOnTheSubmissionLater() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID());

        deliver(submission);

        InboxSubmission stored = stored(submission);
        assertThat(stored.getCaseId()).isEqualTo(submission.getCaseId());
        assertThat(stored.getDestinationId()).isEqualTo(AACHEN_DESTINATION);
        assertThat(stored.getTenant()).isEqualTo("101-aachen");
        assertThat(stored.getServiceIdentifier()).isEqualTo(LEISTUNG);
        assertThat(stored.getRegion()).contains("DE05334");
        assertThat(stored.getDataAsString()).isEqualTo("<Antrag>concrete data</Antrag>");
        assertThat(stored.getDataMimeType()).isEqualTo("application/xml");
        assertThat(stored.getDataSchemaUri()).contains(URI.create("https://schema.example.org/antrag.xsd"));
        assertThat(stored.getApplicationDate()).contains(LocalDate.of(2026, 9, 1));
        assertThat(stored.getMetadata().getReplyChannel().getFitConnect()).isNotNull();
        // Needed for SendableReply.forCase(caseId).setReplyEncryptionKey(...).
        // Same key material, compared by thumbprint: the SDK's
        // EncryptionPublicKey model has no x5c, so the certificate chain
        // never makes it into the metadata in the first place.
        assertThat(stored.getReplyEncryptionKey()).hasValueSatisfying(key ->
                assertThat(thumbprint(key)).isEqualTo(thumbprint(replyKey)));
    }

    @Test
    void rejectsWithTheProblemsAndRecordsTheReason() {
        starter.behaviour = request -> {
            throw new ProcessStartRejectedException("Ausbildungsvertrag incomplete", new DataSchemaViolation());
        };
        IncomingSubmission submission = stubSubmission(UUID.randomUUID());

        deliver(submission);

        verify(submission).reject(List.of(new DataSchemaViolation()));
        verify(submission, never()).accept();
        InboxSubmission stored = stored(submission);
        assertThat(stored.getStatus()).isEqualTo(InboxStatus.REJECTED);
        assertThat(stored.getRejectionReason()).contains("Ausbildungsvertrag incomplete");
        assertThat(stored.getRejectionProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getType()).isEqualTo(new DataSchemaViolation().getType());
            assertThat(problem.getTitle()).isEqualTo("Schema violation");
        });
        assertThat(stored.getResolvedAt()).isPresent();
        assertThat(stored.getProcessDefinition()).isEmpty();
    }

    @Test
    void leavesASubmissionWhoseProcessStartFailedOnTheDeliveryServiceAndRetriesTheStart() {
        UUID submissionId = UUID.randomUUID();
        starter.behaviour = request -> {
            throw new IllegalStateException("Camunda unreachable");
        };
        IncomingSubmission first = stubSubmission(submissionId);

        assertThatThrownBy(() -> deliver(first)).hasMessage("Camunda unreachable");

        verify(first, never()).accept();
        verify(first, never()).reject(anyList());
        InboxSubmission afterFailure = stored(first);
        assertThat(afterFailure.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).hasValueSatisfying(error -> assertThat(error).contains("Camunda unreachable"));

        starter.behaviour = ControllableProcessStarter::succeed;
        IncomingSubmission redelivered = stubSubmission(submissionId);
        deliver(redelivered);

        verify(redelivered).accept();
        InboxSubmission afterRetry = stored(redelivered);
        assertThat(afterRetry.getStatus()).isEqualTo(InboxStatus.ACCEPTED);
        assertThat(afterRetry.getAttempts()).isEqualTo(2);
        assertThat(afterRetry.getLastError()).isEmpty();
    }

    @Test
    void onlyAcceptsOnRedeliveryWhenTheProcessStartedButAcceptFailed() {
        // The integrity case: the process runs, accept() fails on the network,
        // FIT-Connect offers the submission again - the process must NOT be
        // started a second time.
        UUID submissionId = UUID.randomUUID();
        IncomingSubmission first = stubSubmission(submissionId);
        doThrow(new IllegalStateException("network down")).when(first).accept();

        assertThatThrownBy(() -> deliver(first)).hasMessage("network down");
        assertThat(stored(first).getStatus()).isEqualTo(InboxStatus.PROCESS_STARTED);

        IncomingSubmission redelivered = stubSubmission(submissionId);
        deliver(redelivered);

        assertThat(starter.started).containsExactly(submissionId);
        verify(redelivered).accept();
        assertThat(stored(redelivered).getStatus()).isEqualTo(InboxStatus.ACCEPTED);
    }

    @Test
    void onlyRejectsOnRedeliveryWhenRejectFailed() {
        UUID submissionId = UUID.randomUUID();
        starter.behaviour = request -> {
            throw new ProcessStartRejectedException("Ausbildungsvertrag incomplete", new DataSchemaViolation());
        };
        IncomingSubmission first = stubSubmission(submissionId);
        doThrow(new IllegalStateException("network down")).when(first).reject(anyList());

        assertThatThrownBy(() -> deliver(first)).hasMessage("network down");
        assertThat(stored(first).getStatus()).isEqualTo(InboxStatus.REJECTION_PENDING);

        IncomingSubmission redelivered = stubSubmission(submissionId);
        deliver(redelivered);

        assertThat(starter.started).containsExactly(submissionId);
        verify(redelivered).reject(List.<Problem>of(new DataSchemaViolation()));
        assertThat(stored(redelivered).getStatus()).isEqualTo(InboxStatus.REJECTED);
    }

    @Test
    void treatsANullStartedProcessAsAFailure() {
        starter.behaviour = request -> null;
        IncomingSubmission submission = stubSubmission(UUID.randomUUID());

        assertThatThrownBy(() -> deliver(submission)).hasMessageContaining("returned null");

        verify(submission, never()).accept();
        assertThat(stored(submission).getStatus()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    void doesNothingForASubmissionThatIsAlreadyResolved() {
        // e.g. a second replica downloaded it before the first one's accept()
        UUID submissionId = UUID.randomUUID();
        deliver(stubSubmission(submissionId));

        IncomingSubmission concurrent = stubSubmission(submissionId);
        deliver(concurrent);

        assertThat(starter.started).containsExactly(submissionId);
        verify(concurrent, never()).accept();
        verify(concurrent, never()).reject(anyList());
    }

    private void deliver(IncomingSubmission submission) {
        listener.onAntrag(new SubmissionReceivedEvent(this, submission));
    }

    private InboxSubmission stored(IncomingSubmission submission) {
        return repository.findById(submission.getSubmissionId()).orElseThrow();
    }

    private static String thumbprint(JWK key) {
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private IncomingSubmission stubSubmission(UUID submissionId) {
        MetadataV2 metadata = new MetadataV2();
        metadata.setSchema("https://schema.fitko.de/fit-connect/metadata/2.1.0/metadata.schema.json");
        metadata.setReplyChannel(ReplyChannel.ofFitConnect(replyKey, List.of()));

        IncomingSubmission submission = mock(IncomingSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getCaseId()).thenReturn(UUID.nameUUIDFromBytes(submissionId.toString().getBytes(StandardCharsets.UTF_8)));
        when(submission.getDestinationId()).thenReturn(AACHEN_DESTINATION);
        when(submission.getServiceType()).thenReturn(new PublicService("Ausbildungsvertrag", LEISTUNG));
        when(submission.getRegion()).thenReturn(Optional.of("DE05334"));
        when(submission.getDataAsBytes()).thenReturn("<Antrag>concrete data</Antrag>".getBytes(StandardCharsets.UTF_8));
        when(submission.getDataMimeType()).thenReturn("application/xml");
        when(submission.getDataSchemaUri()).thenReturn(URI.create("https://schema.example.org/antrag.xsd"));
        when(submission.getMetadata()).thenReturn(metadata);
        when(submission.getApplicationDate()).thenReturn(Optional.of(LocalDate.of(2026, 9, 1)));
        when(submission.getAttachments()).thenReturn(List.of(Attachment.fromByteArray(
                "%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8), "application/pdf", "nachweis.pdf", "Nachweis")));
        return submission;
    }

    @Configuration(proxyBeanMethods = false)
    static class StarterConfig {

        @Bean
        ControllableProcessStarter controllableProcessStarter() {
            return new ControllableProcessStarter();
        }
    }

    static class ControllableProcessStarter implements ProcessStarter {

        final List<UUID> started = new ArrayList<>();
        final List<List<String>> attachmentFileNames = new ArrayList<>();
        Function<ProcessStartRequest, StartedProcess> behaviour = ControllableProcessStarter::succeed;

        void reset() {
            started.clear();
            attachmentFileNames.clear();
            behaviour = ControllableProcessStarter::succeed;
        }

        static StartedProcess succeed(ProcessStartRequest request) {
            return new StartedProcess("ausbildungsvertrag-eintragung", "instance-" + request.submission().getSubmissionId());
        }

        @Override
        public StartedProcess start(ProcessStartRequest request) {
            started.add(request.submission().getSubmissionId());
            attachmentFileNames.add(request.submission().getAttachments().stream().map(Attachment::getFileName).toList());
            return behaviour.apply(request);
        }
    }
}
