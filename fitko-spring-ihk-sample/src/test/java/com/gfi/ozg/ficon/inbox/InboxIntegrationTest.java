package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.ficon.processstarter.ProcessStartRejectedException;
import com.gfi.ozg.ficon.processstarter.ProcessStartRequest;
import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import com.gfi.ozg.ficon.routing.AntragRoutingListener;
import com.gfi.ozg.ficon.support.IhkApplicationTestSupport;
import com.gfi.ozg.ficon.support.TestJwkKeys;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The inbox end to end against H2: {@link AntragRoutingListener} storing a
 * submission, {@link AntragDispatcher} handing it to a {@link ProcessStarter}
 * and recording the outcome. The Leistung used here has no tenant mapping in
 * {@code application.yaml}, so it goes to {@code
 * default-process-starter-class} - overridden to {@link
 * ControllableProcessStarter}. {@code retry-delay=0s} makes a failed
 * submission due again immediately.
 */
@TestPropertySource(properties = {
        "antrag-routing.default-process-starter-class=com.gfi.ozg.ficon.inbox.InboxIntegrationTest$ControllableProcessStarter",
        "antrag-dispatch.retry-delay=0s",
        "antrag-dispatch.max-attempts=3"
})
@Import(InboxIntegrationTest.StarterConfig.class)
class InboxIntegrationTest extends IhkApplicationTestSupport {

    private static final String LEISTUNG = "urn:de:fim:leika:leistung:99050035009000";

    @Autowired
    AntragRoutingListener listener;

    @Autowired
    AntragDispatcher dispatcher;

    @Autowired
    InboxSubmissionRepository repository;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ControllableProcessStarter starter;

    private final JWK replyKey = TestJwkKeys.encryptionPublicKey();

    @BeforeEach
    void cleanInbox() {
        repository.deleteAll();
        starter.reset();
    }

    @Test
    void storesEverythingNeededToWorkOnTheSubmissionLaterAndAcceptsIt() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID());

        listener.onAntrag(new SubmissionReceivedEvent(this, submission));

        verify(submission).accept();
        transactions.executeWithoutResult(status -> {
            InboxSubmission stored = repository.findById(submission.getSubmissionId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(InboxStatus.PENDING);
            assertThat(stored.getCaseId()).isEqualTo(submission.getCaseId());
            assertThat(stored.getDestinationId()).isEqualTo(AACHEN_DESTINATION);
            assertThat(stored.getTenant()).isEqualTo("101-aachen");
            assertThat(stored.getServiceIdentifier()).isEqualTo(LEISTUNG);
            assertThat(stored.getRegion()).contains("DE05334");
            assertThat(stored.getDataAsString()).isEqualTo("<Antrag>concrete data</Antrag>");
            assertThat(stored.getDataMimeType()).isEqualTo("application/xml");
            assertThat(stored.getDataSchemaUri()).contains(URI.create("https://schema.example.org/antrag.xsd"));
            assertThat(stored.getApplicationDate()).contains(LocalDate.of(2026, 9, 1));
            // Needed for SendableReply.forCase(caseId).setReplyEncryptionKey(...).
            // Same key material, compared by thumbprint: the SDK's
            // EncryptionPublicKey model has no x5c, so the certificate chain
            // never makes it into the metadata in the first place.
            assertThat(stored.getReplyEncryptionKey()).hasValueSatisfying(key ->
                    assertThat(thumbprint(key)).isEqualTo(thumbprint(replyKey)));
            assertThat(stored.getMetadata().getReplyChannel().getFitConnect()).isNotNull();
            assertThat(stored.getAttachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.getFileName()).isEqualTo("nachweis.pdf");
                assertThat(attachment.getMimeType()).isEqualTo("application/pdf");
                assertThat(attachment.getDataAsString()).isEqualTo("%PDF-1.7 ...");
            });
        });
    }

    @Test
    void acceptsARedeliveredSubmissionWithoutStoringItTwice() {
        UUID submissionId = UUID.randomUUID();
        listener.onAntrag(new SubmissionReceivedEvent(this, stubSubmission(submissionId)));

        // accept() "failed" - FIT-Connect delivers the same submission again.
        IncomingSubmission redelivered = stubSubmission(submissionId);
        listener.onAntrag(new SubmissionReceivedEvent(this, redelivered));

        verify(redelivered).accept();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void dispatchesAStoredSubmissionExactlyOnce() {
        UUID submissionId = store();

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(starter.started).containsExactly(submissionId);
        // Lazy attachment data is readable inside start()
        assertThat(starter.attachmentCounts).containsExactly(1);
        InboxSubmission stored = repository.findById(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InboxStatus.STARTED);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getProcessStarterClass()).contains(ControllableProcessStarter.class.getName());
        assertThat(stored.getProcessDefinition()).contains("ausbildungsvertrag-eintragung");
        assertThat(stored.getProcessInstanceId()).contains("instance-" + submissionId);
        assertThat(stored.getProcessStartedAt()).isPresent();
        assertThat(stored.getProcessedAt()).isEqualTo(stored.getProcessStartedAt());
    }

    @Test
    void marksARejectedSubmissionRejectedAndDoesNotRetryIt() {
        UUID submissionId = store();
        starter.behaviour = request -> {
            throw new ProcessStartRejectedException("Ausbildungsvertrag incomplete");
        };

        dispatcher.dispatchDue();
        dispatcher.dispatchDue();

        assertThat(starter.started).hasSize(1);
        InboxSubmission stored = repository.findById(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InboxStatus.REJECTED);
        assertThat(stored.getLastError()).contains("Ausbildungsvertrag incomplete");
        assertThat(stored.getProcessDefinition()).isEmpty();
        assertThat(stored.getProcessStartedAt()).isEmpty();
    }

    @Test
    void retriesATransientFailureUntilItSucceeds() {
        UUID submissionId = store();
        starter.behaviour = request -> {
            throw new IllegalStateException("Camunda unreachable");
        };

        dispatcher.dispatchDue();

        InboxSubmission afterFailure = repository.findById(submissionId).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(InboxStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).hasValueSatisfying(error -> assertThat(error).contains("Camunda unreachable"));

        starter.behaviour = ControllableProcessStarter::succeed;
        dispatcher.dispatchDue();

        InboxSubmission afterRetry = repository.findById(submissionId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(InboxStatus.STARTED);
        assertThat(afterRetry.getAttempts()).isEqualTo(2);
        assertThat(afterRetry.getLastError()).isEmpty();
    }

    @Test
    void treatsANullStartedProcessAsAFailure() {
        UUID submissionId = store();
        starter.behaviour = request -> null;

        dispatcher.dispatchDue();

        InboxSubmission stored = repository.findById(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InboxStatus.PENDING);
        assertThat(stored.getLastError()).hasValueSatisfying(error -> assertThat(error).contains("returned null"));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        UUID submissionId = store();
        starter.behaviour = request -> {
            throw new IllegalStateException("Camunda unreachable");
        };

        for (int i = 0; i < 5; i++) {
            dispatcher.dispatchDue();
        }

        assertThat(starter.started).hasSize(3);
        InboxSubmission stored = repository.findById(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InboxStatus.FAILED);
        assertThat(stored.getAttempts()).isEqualTo(3);
    }

    private static String thumbprint(JWK key) {
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID store() {
        IncomingSubmission submission = stubSubmission(UUID.randomUUID());
        listener.onAntrag(new SubmissionReceivedEvent(this, submission));
        return submission.getSubmissionId();
    }

    private IncomingSubmission stubSubmission(UUID submissionId) {
        MetadataV2 metadata = new MetadataV2();
        metadata.setSchema("https://schema.fitko.de/fit-connect/metadata/2.1.0/metadata.schema.json");
        metadata.setReplyChannel(ReplyChannel.ofFitConnect(replyKey, List.of()));

        IncomingSubmission submission = mock(IncomingSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getCaseId()).thenReturn(UUID.randomUUID());
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
        final List<Integer> attachmentCounts = new ArrayList<>();
        Function<ProcessStartRequest, StartedProcess> behaviour = ControllableProcessStarter::succeed;

        void reset() {
            started.clear();
            attachmentCounts.clear();
            behaviour = ControllableProcessStarter::succeed;
        }

        static StartedProcess succeed(ProcessStartRequest request) {
            return new StartedProcess("ausbildungsvertrag-eintragung", "instance-" + request.submission().getSubmissionId());
        }

        @Override
        public StartedProcess start(ProcessStartRequest request) {
            started.add(request.submission().getSubmissionId());
            attachmentCounts.add(request.submission().getAttachments().size());
            return behaviour.apply(request);
        }
    }
}
