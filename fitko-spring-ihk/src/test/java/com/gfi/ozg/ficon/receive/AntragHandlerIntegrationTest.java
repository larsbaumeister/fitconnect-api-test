package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.inbox.InboxStatus;
import com.gfi.ozg.ficon.inbox.InboxSubmission;
import com.gfi.ozg.ficon.inbox.InboxSubmissionRepository;
import com.gfi.ozg.ficon.inbox.SubmissionInbox;
import com.gfi.ozg.ficon.processstarter.ProcessStartRejectedException;
import com.gfi.ozg.ficon.processstarter.ProcessStarterRouter;
import com.gfi.ozg.ficon.support.ControllableProcessStarter;
import com.gfi.ozg.ficon.support.PostgresTestSupport;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AntragHandler} against PostgreSQL: start, accept/reject, what a
 * re-delivery does after each possible failure, and two replicas handling the
 * same submission at once. A delivery is a mocked SDK {@link
 * ReceivedSubmission}; a re-delivery is a fresh one with the same id.
 */
class AntragHandlerIntegrationTest extends PostgresTestSupport {

    private static final String TENANT = "101-aachen";
    private static final UUID DESTINATION = UUID.randomUUID();
    /** Not mapped in application.yaml - goes to the default, ControllableProcessStarter. */
    private static final String LEISTUNG = "urn:de:fim:leika:leistung:99050035009000";

    @Autowired
    AntragHandler handler;

    @Autowired
    InboxSubmissionRepository repository;

    @Autowired
    ControllableProcessStarter starter;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        starter.reset();
    }

    @Test
    void startsTheProcessThenAcceptsAndRecordsBoth() {
        ReceivedSubmission submission = submission(UUID.randomUUID());

        handler.handle(submission, TENANT);

        verify(submission).acceptSubmission();
        assertThat(starter.attachmentFileNames).containsExactly(List.of("nachweis.pdf"));
        InboxSubmission row = row(submission);
        assertThat(row.getStatus()).isEqualTo(InboxStatus.ACCEPTED);
        assertThat(row.getCaseId()).isEqualTo(submission.getCaseId());
        assertThat(row.getDestinationId()).isEqualTo(DESTINATION);
        assertThat(row.getTenant()).isEqualTo(TENANT);
        assertThat(row.getServiceIdentifier()).isEqualTo(LEISTUNG);
        assertThat(row.getProcessStarterClass()).contains(ControllableProcessStarter.class.getName());
        assertThat(row.getProcessDefinition()).contains("ausbildungsvertrag-eintragung");
        assertThat(row.getProcessInstanceId()).contains("instance-" + submission.getSubmissionId());
        assertThat(row.getProcessStartedAt()).isPresent();
        assertThat(row.getResolvedAt()).isPresent();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void rejectsWithTheProblemsAndRecordsTheReason() {
        starter.behaviour = s -> {
            throw new ProcessStartRejectedException("Ausbildungsvertrag incomplete", new DataSchemaViolation());
        };
        ReceivedSubmission submission = submission(UUID.randomUUID());

        handler.handle(submission, TENANT);

        verify(submission).rejectSubmission(List.of(new DataSchemaViolation()));
        verify(submission, never()).acceptSubmission();
        InboxSubmission row = row(submission);
        assertThat(row.getStatus()).isEqualTo(InboxStatus.REJECTED);
        assertThat(row.getRejectionReason()).contains("Ausbildungsvertrag incomplete");
        assertThat(row.getRejectionProblems()).singleElement()
                .satisfies(problem -> assertThat(problem.getType()).isEqualTo(new DataSchemaViolation().getType()));
        assertThat(row.getProcessDefinition()).isEmpty();
    }

    @Test
    void startsTheProcessAgainAfterAFailedStart() {
        UUID submissionId = UUID.randomUUID();
        starter.behaviour = s -> {
            throw new IllegalStateException("Camunda unreachable");
        };
        ReceivedSubmission first = submission(submissionId);

        assertThatThrownBy(() -> handler.handle(first, TENANT)).hasMessage("Camunda unreachable");

        verify(first, never()).acceptSubmission();
        InboxSubmission afterFailure = row(first);
        assertThat(afterFailure.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).hasValueSatisfying(e -> assertThat(e).contains("Camunda unreachable"));

        starter.behaviour = ControllableProcessStarter::succeed;
        ReceivedSubmission redelivered = submission(submissionId);
        handler.handle(redelivered, TENANT);

        verify(redelivered).acceptSubmission();
        InboxSubmission afterRetry = row(redelivered);
        assertThat(afterRetry.getStatus()).isEqualTo(InboxStatus.ACCEPTED);
        assertThat(afterRetry.getAttempts()).isEqualTo(2);
        assertThat(afterRetry.getLastError()).isEmpty();
    }

    @Test
    void onlyAcceptsOnRedeliveryWhenTheProcessStartedButAcceptFailed() {
        UUID submissionId = UUID.randomUUID();
        ReceivedSubmission first = submission(submissionId);
        doThrow(new IllegalStateException("network down")).when(first).acceptSubmission();

        assertThatThrownBy(() -> handler.handle(first, TENANT)).hasMessage("network down");
        assertThat(row(first).getStatus()).isEqualTo(InboxStatus.PROCESS_STARTED);

        ReceivedSubmission redelivered = submission(submissionId);
        handler.handle(redelivered, TENANT);

        assertThat(starter.started).containsExactly(submissionId);
        verify(redelivered).acceptSubmission();
        assertThat(row(redelivered).getStatus()).isEqualTo(InboxStatus.ACCEPTED);
    }

    @Test
    void onlyRejectsOnRedeliveryWhenRejectFailed() {
        UUID submissionId = UUID.randomUUID();
        starter.behaviour = s -> {
            throw new ProcessStartRejectedException("Ausbildungsvertrag incomplete", new DataSchemaViolation());
        };
        ReceivedSubmission first = submission(submissionId);
        doThrow(new IllegalStateException("network down")).when(first).rejectSubmission(anyList());

        assertThatThrownBy(() -> handler.handle(first, TENANT)).hasMessage("network down");
        assertThat(row(first).getStatus()).isEqualTo(InboxStatus.REJECTION_PENDING);

        ReceivedSubmission redelivered = submission(submissionId);
        handler.handle(redelivered, TENANT);

        assertThat(starter.started).containsExactly(submissionId);
        verify(redelivered).rejectSubmission(List.<Problem>of(new DataSchemaViolation()));
        assertThat(row(redelivered).getStatus()).isEqualTo(InboxStatus.REJECTED);
    }

    @Test
    void treatsANullStartedProcessAsAFailure() {
        starter.behaviour = s -> null;
        ReceivedSubmission submission = submission(UUID.randomUUID());

        assertThatThrownBy(() -> handler.handle(submission, TENANT)).hasMessageContaining("returned null");

        verify(submission, never()).acceptSubmission();
        assertThat(row(submission).getStatus()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    void doesNothingForASubmissionThatIsAlreadyResolved() {
        UUID submissionId = UUID.randomUUID();
        handler.handle(submission(submissionId), TENANT);

        ReceivedSubmission again = submission(submissionId);
        handler.handle(again, TENANT);

        assertThat(starter.started).containsExactly(submissionId);
        verify(again, never()).acceptSubmission();
        verify(again, never()).rejectSubmission(anyList());
    }

    @Test
    void aSecondReplicaSkipsTheSubmissionWhileTheFirstIsStartingItsProcess() throws Exception {
        UUID submissionId = UUID.randomUUID();
        CountDownLatch inStart = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        starter.behaviour = s -> {
            inStart.countDown();
            await(release);
            return ControllableProcessStarter.succeed(s);
        };
        ReceivedSubmission onReplicaA = submission(submissionId);
        CompletableFuture<Void> replicaA = CompletableFuture.runAsync(() -> handler.handle(onReplicaA, TENANT));
        await(inStart);

        // Replica B: same submission while A holds the row lock - must neither
        // wait nor start the process nor resolve the submission.
        ReceivedSubmission onReplicaB = submission(submissionId);
        CompletableFuture.runAsync(() -> handler.handle(onReplicaB, TENANT)).get(10, TimeUnit.SECONDS);

        assertThat(starter.started).containsExactly(submissionId);
        verify(onReplicaB, never()).acceptSubmission();
        verify(onReplicaB, never()).rejectSubmission(anyList());

        release.countDown();
        replicaA.get(10, TimeUnit.SECONDS);
        verify(onReplicaA).acceptSubmission();
        assertThat(row(onReplicaA).getStatus()).isEqualTo(InboxStatus.ACCEPTED);
        assertThat(starter.started).containsExactly(submissionId);
    }

    @Test
    void leavesASubmissionWithoutProcessStarterUntouched(@Autowired SubmissionInbox inbox,
                                                         @Autowired ApplicationContext context) {
        AntragHandler withoutDefault = new AntragHandler(
                new ProcessStarterRouter(new ProcessStarterRouter.Properties(Map.of(), null), context), inbox);
        ReceivedSubmission submission = submission(UUID.randomUUID());

        assertThatThrownBy(() -> withoutDefault.handle(submission, TENANT))
                .hasMessageContaining("No ProcessStarter configured");

        assertThat(repository.findById(submission.getSubmissionId())).isEmpty();
        verify(submission, never()).acceptSubmission();
        verify(submission, never()).rejectSubmission(anyList());
    }

    private InboxSubmission row(ReceivedSubmission submission) {
        return repository.findById(submission.getSubmissionId()).orElseThrow();
    }

    private static ReceivedSubmission submission(UUID submissionId) {
        ReceivedSubmission submission = mock(ReceivedSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getCaseId()).thenReturn(UUID.nameUUIDFromBytes(submissionId.toString().getBytes(StandardCharsets.UTF_8)));
        when(submission.getDestinationId()).thenReturn(DESTINATION);
        when(submission.getServiceType()).thenReturn(new PublicService("Ausbildungsvertrag", LEISTUNG));
        when(submission.getAttachments()).thenReturn(List.of(Attachment.fromByteArray(
                "%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8), "application/pdf", "nachweis.pdf", "Nachweis")));
        return submission;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
