package com.example.gewerbeamt.receive;

import com.example.gewerbeamt.Leistung;
import com.gfi.ozg.fitko.spring.receive.AntragReceivedEvent;
import com.gfi.ozg.fitko.spring.receive.ReceivedAntrag;
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
 * Receive-side tests without a Spring context: build a {@link ReceivedAntrag}
 * test double, wrap it in an {@link AntragReceivedEvent}, and call the
 * listener method directly. (fitko-spring's own {@code src/test} shows the
 * full-context variant, driving a mocked SDK {@code SubscriberClient} through
 * a real poll cycle.)
 *
 * <p>{@code ReceivedAntrag} is {@code final}; Mockito's inline mock maker,
 * on by default with Spring Boot, mocks it fine.
 */
class GewerbeanmeldungHandlerTest {

    private static final String VALID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Gewerbeanmeldung xmlns="https://example.org/schema/gewerbeanmeldung/v1">
                <Betrieb>Baeckerei Mustermann</Betrieb>
            </Gewerbeanmeldung>
            """;

    private ReceivedAntragStore store;
    private GewerbeanmeldungHandler handler;

    @BeforeEach
    void setUp() {
        store = new ReceivedAntragStore();
        handler = new GewerbeanmeldungHandler(store);
    }

    @Test
    void storesAndAcceptsAValidSubmission() {
        UUID submissionId = UUID.randomUUID();
        ReceivedAntrag antrag = stubAntrag(submissionId, VALID_XML);

        handler.onGewerbeanmeldung(new AntragReceivedEvent(this, antrag));

        assertThat(store.contains(submissionId)).isTrue();
        assertThat(store.all()).singleElement().satisfies(stored -> {
            assertThat(stored.submissionId()).isEqualTo(submissionId);
            assertThat(stored.serviceId()).isEqualTo(Leistung.GEWERBEANMELDUNG_LEIKA);
            assertThat(stored.data()).contains("Baeckerei Mustermann");
        });
        verify(antrag).accept();
    }

    @Test
    void isIdempotentAcrossRedeliveryOfTheSameSubmission() {
        UUID submissionId = UUID.randomUUID();

        handler.onGewerbeanmeldung(new AntragReceivedEvent(this, stubAntrag(submissionId, VALID_XML)));
        handler.onGewerbeanmeldung(new AntragReceivedEvent(this, stubAntrag(submissionId, VALID_XML)));

        assertThat(store.all()).hasSize(1);
    }

    @Test
    void rejectsASubmissionWhosePayloadIsNotAGewerbeanmeldung() {
        UUID submissionId = UUID.randomUUID();
        ReceivedAntrag antrag = stubAntrag(submissionId, "<SomethingElse/>");

        handler.onGewerbeanmeldung(new AntragReceivedEvent(this, antrag));

        assertThat(store.contains(submissionId)).isFalse();
        verify(antrag).reject(any(DataSchemaViolation.class));
        verify(antrag, never()).accept();
    }

    private static ReceivedAntrag stubAntrag(UUID submissionId, String data) {
        ReceivedAntrag antrag = mock(ReceivedAntrag.class);
        when(antrag.getSubmissionId()).thenReturn(submissionId);
        when(antrag.getCaseId()).thenReturn(UUID.randomUUID());
        when(antrag.getDestinationId()).thenReturn(UUID.randomUUID());
        when(antrag.getServiceType())
                .thenReturn(new PublicService(Leistung.GEWERBEANMELDUNG_NAME, Leistung.GEWERBEANMELDUNG_LEIKA));
        when(antrag.getDataAsString()).thenReturn(data);
        when(antrag.getDataAsBytes()).thenReturn(data.getBytes());
        when(antrag.getDataMimeType()).thenReturn("text/xml");
        when(antrag.getAttachments()).thenReturn(List.of());
        return antrag;
    }
}
