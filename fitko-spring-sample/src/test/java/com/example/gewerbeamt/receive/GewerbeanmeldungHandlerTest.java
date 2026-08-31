package com.example.gewerbeamt.receive;

import com.example.gewerbeamt.Leistung;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Receive-side tests without a Spring context: build a {@link IncomingSubmission}
 * test double, wrap it in an {@link SubmissionReceivedEvent}, and call the
 * listener method directly. (fitko-spring's own {@code src/test} shows the
 * full-context variant, driving a mocked SDK {@code SubscriberClient} through
 * a real poll cycle.)
 *
 * <p>{@code IncomingSubmission} is {@code final}; Mockito's inline mock maker,
 * on by default with Spring Boot, mocks it fine.
 */
class GewerbeanmeldungHandlerTest {

    private static final String VALID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Gewerbeanmeldung xmlns="https://example.org/schema/gewerbeanmeldung/v1">
                <Betrieb>Baeckerei Mustermann</Betrieb>
            </Gewerbeanmeldung>
            """;

    private ReceivedSubmissionStore store;
    private GewerbeanmeldungHandler handler;

    @BeforeEach
    void setUp() {
        store = new ReceivedSubmissionStore();
        handler = new GewerbeanmeldungHandler(store);
    }

    @Test
    void storesAndAcceptsAValidSubmission() {
        UUID submissionId = UUID.randomUUID();
        IncomingSubmission submission = stubSubmission(submissionId, VALID_XML);

        handler.onGewerbeanmeldung(new SubmissionReceivedEvent(this, submission));

        assertThat(store.contains(submissionId)).isTrue();
        assertThat(store.all()).singleElement().satisfies(stored -> {
            assertThat(stored.submissionId()).isEqualTo(submissionId);
            assertThat(stored.serviceId()).isEqualTo(Leistung.GEWERBEANMELDUNG_LEIKA);
            assertThat(stored.data()).contains("Baeckerei Mustermann");
        });
        verify(submission).accept();
    }

    @Test
    void isIdempotentAcrossRedeliveryOfTheSameSubmission() {
        UUID submissionId = UUID.randomUUID();

        handler.onGewerbeanmeldung(new SubmissionReceivedEvent(this, stubSubmission(submissionId, VALID_XML)));
        handler.onGewerbeanmeldung(new SubmissionReceivedEvent(this, stubSubmission(submissionId, VALID_XML)));

        assertThat(store.all()).hasSize(1);
    }

    @Test
    void rejectsASubmissionWhosePayloadIsNotAGewerbeanmeldung() {
        UUID submissionId = UUID.randomUUID();
        IncomingSubmission submission = stubSubmission(submissionId, "<SomethingElse/>");

        handler.onGewerbeanmeldung(new SubmissionReceivedEvent(this, submission));

        assertThat(store.contains(submissionId)).isFalse();
        verify(submission).reject(any(DataSchemaViolation.class));
        verify(submission, never()).accept();
    }

    private static IncomingSubmission stubSubmission(UUID submissionId, String data) {
        IncomingSubmission submission = mock(IncomingSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getCaseId()).thenReturn(UUID.randomUUID());
        when(submission.getDestinationId()).thenReturn(UUID.randomUUID());
        when(submission.getServiceType())
                .thenReturn(new PublicService(Leistung.GEWERBEANMELDUNG_NAME, Leistung.GEWERBEANMELDUNG_LEIKA));
        when(submission.getDataAsString()).thenReturn(data);
        when(submission.getDataAsBytes()).thenReturn(data.getBytes());
        when(submission.getDataMimeType()).thenReturn("text/xml");
        when(submission.getAttachments()).thenReturn(List.of());
        return submission;
    }
}
