package com.example.gewerbeamt.send;

import com.example.gewerbeamt.Leistung;
import com.gfi.ozg.fitko.spring.send.AntragSendException;
import com.gfi.ozg.fitko.spring.send.AntragSender;
import com.gfi.ozg.fitko.spring.send.AntragToSend;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The send side is pure application code once {@link AntragSender} is
 * injected, so it unit-tests with a plain Mockito mock - no Spring context,
 * no SDK, no network. This is the pattern to copy for your own send-side
 * tests.
 */
@ExtendWith(MockitoExtension.class)
class GewerbeanmeldungServiceTest {

    @Mock
    AntragSender antragSender;

    @InjectMocks
    GewerbeanmeldungService service;

    @Captor
    ArgumentCaptor<AntragToSend> antragCaptor;

    private final GewerbeanmeldungRequest request = new GewerbeanmeldungRequest(
            UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7"),
            "Baeckerei Mustermann",
            "Erika Mustermann",
            "erika@example.com");

    @Test
    void buildsTheAntragFromTheRequestAndSendsIt() {
        UUID submissionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(antragSender.send(any(AntragToSend.class)))
                .thenReturn(new SentSubmission(request.destinationId(), caseId, submissionId));

        SentSubmission sent = service.submit(request);

        assertThat(sent.getSubmissionId()).isEqualTo(submissionId);
        assertThat(sent.getCaseId()).isEqualTo(caseId);

        verify(antragSender).send(antragCaptor.capture());
        AntragToSend antrag = antragCaptor.getValue();
        assertThat(antrag.getDestinationId()).isEqualTo(request.destinationId());
        assertThat(antrag.getServiceId()).isEqualTo(Leistung.GEWERBEANMELDUNG_LEIKA);
        assertThat(antrag.getDataFormat()).isEqualTo(DataFormat.XML);
        assertThat(antrag.getData()).contains("Baeckerei Mustermann").contains("<Gewerbeanmeldung");
        assertThat(antrag.getReplyChannelEmail()).isEqualTo("erika@example.com");
        assertThat(antrag.getAttachments()).hasSize(1);
    }

    @Test
    void propagatesASendFailure() {
        when(antragSender.send(any(AntragToSend.class)))
                .thenThrow(new AntragSendException("delivery service said no"));

        assertThatThrownBy(() -> service.submit(request))
                .isInstanceOf(AntragSendException.class)
                .hasMessageContaining("delivery service said no");
    }
}
