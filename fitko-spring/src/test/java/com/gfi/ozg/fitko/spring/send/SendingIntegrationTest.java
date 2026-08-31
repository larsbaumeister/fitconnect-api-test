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
        "fitconnect.sender.client-secret=test-client-secret"
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
    SubmissionSender submissionSender;

    @Test
    void sendsToTheGivenDestination() throws FitConnectSenderException {
        UUID destinationId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(senderClient.send(any(SendableSubmission.class))).thenReturn(new SentSubmission(destinationId, caseId, submissionId));

        SubmissionToSend submission = SubmissionToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.XML, "<test>Hello</test>", URI.create("https://example.org/schema.xsd"))
                .destinationId(destinationId)
                .build();

        SentSubmission result = submissionSender.send(submission);

        assertThat(result.getSubmissionId()).isEqualTo(submissionId);
        assertThat(result.getDestinationId()).isEqualTo(destinationId);
        verify(senderClient).send(argThat((SendableSubmission sent) -> sent.getDestinationId().equals(destinationId)));
    }

    @Test
    void rejectsASubmissionWithNoDestinationId() {
        SubmissionToSend submission = SubmissionToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.JSON, "{}", URI.create("https://example.org/schema.json"))
                .build();

        assertThatThrownBy(() -> submissionSender.send(submission))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destinationId");
    }

    @Test
    void wrapsAFitConnectSenderExceptionInAnUncheckedSubmissionSendException() throws FitConnectSenderException {
        when(senderClient.send(any(SendableSubmission.class))).thenThrow(new FitConnectSenderException("boom"));

        SubmissionToSend submission = SubmissionToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.XML, "<test/>", URI.create("https://example.org/schema.xsd"))
                .destinationId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> submissionSender.send(submission))
                .isInstanceOf(SubmissionSendException.class)
                .hasCauseInstanceOf(FitConnectSenderException.class);
    }
}
