package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.api.domain.sender.SendableSubmission;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectSenderException;
import dev.fitko.fitconnect.client.SenderClient;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectAutoConfiguration;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectSenderAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full Spring context wiring for the sending side, with the FIT-Connect SDK's
 * {@link SenderClient} replaced by a Mockito mock: no real network calls, no
 * real key material, isolated and deterministic.
 */
@SpringBootTest(classes = SendingIntegrationTest.TestConfig.class, properties = {
        "fitconnect.receiver.enabled=false",
        "fitconnect.sender.client-id=test-client-id",
        "fitconnect.sender.client-secret=test-client-secret",
        "fitconnect.destination-id=9f6bb611-df46-494a-9a98-a253f1362dc7"
})
@DirtiesContext
class SendingIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({FitConnectAutoConfiguration.class, FitConnectSenderAutoConfiguration.class})
    static class TestConfig {
    }

    @MockitoBean
    SenderClient senderClient;

    @Autowired
    AntragSender antragSender;

    @Test
    void sendsThroughTheConfiguredDestinationByDefault() throws FitConnectSenderException {
        UUID destinationId = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
        UUID submissionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(senderClient.send(any(SendableSubmission.class))).thenReturn(new SentSubmission(destinationId, caseId, submissionId));

        AntragToSend antrag = AntragToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.XML, "<test>Hello</test>", URI.create("https://example.org/schema.xsd"))
                .build();

        SentSubmission result = antragSender.send(antrag);

        assertThat(result.getSubmissionId()).isEqualTo(submissionId);
        assertThat(result.getDestinationId()).isEqualTo(destinationId);
        verify(senderClient).send(any(SendableSubmission.class));
    }

    @Test
    void perAntragDestinationOverridesTheConfiguredDefault() throws FitConnectSenderException {
        UUID overrideDestinationId = UUID.randomUUID();
        when(senderClient.send(any(SendableSubmission.class))).thenReturn(new SentSubmission(overrideDestinationId, UUID.randomUUID(), UUID.randomUUID()));

        AntragToSend antrag = AntragToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.JSON, "{}", URI.create("https://example.org/schema.json"))
                .destinationId(overrideDestinationId)
                .build();

        antragSender.send(antrag);

        verify(senderClient).send(argThat((SendableSubmission submission) -> submission.getDestinationId().equals(overrideDestinationId)));
    }

    @Test
    void wrapsAFitConnectSenderExceptionInAnUncheckedAntragSendException() throws FitConnectSenderException {
        when(senderClient.send(any(SendableSubmission.class))).thenThrow(new FitConnectSenderException("boom"));

        AntragToSend antrag = AntragToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.XML, "<test/>", URI.create("https://example.org/schema.xsd"))
                .build();

        assertThatThrownBy(() -> antragSender.send(antrag))
                .isInstanceOf(AntragSendException.class)
                .hasCauseInstanceOf(FitConnectSenderException.class);
    }
}
