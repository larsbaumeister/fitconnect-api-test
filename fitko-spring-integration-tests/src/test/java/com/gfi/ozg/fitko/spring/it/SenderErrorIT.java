package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionSendException;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT-10a - a send that FIT-Connect cannot fulfil surfaces as an unchecked
 * {@link SubmissionSendException}, not a partial success. Sender only; no
 * receiver, so no destination keys are needed.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "fitconnect.receiver.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SenderErrorIT {

    @Autowired
    SubmissionSender submissionSender;

    @BeforeAll
    static void requireSenderCredentials() {
        ITCredentials.gate(ITCredentials.SENDER_VARS);
    }

    @DynamicPropertySource
    static void senderProperties(DynamicPropertyRegistry registry) {
        if (!ITCredentials.allSet(ITCredentials.SENDER_VARS)) {
            return;
        }
        registry.add("fitconnect.environment", ITCredentials::environment);
        registry.add("fitconnect.sender.client-id", ITCredentials::senderClientId);
        registry.add("fitconnect.sender.client-secret", ITCredentials::senderClientSecret);
    }

    @Test
    void sendingToANonExistentDestinationThrowsSubmissionSendException() {
        UUID nonExistentDestination = UUID.randomUUID();
        SubmissionToSend submission = SubmissionToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "fitko-spring IT (sender error)",
                        DataFormat.XML, Payloads.xml(Payloads.newMarker(getClass())),
                        URI.create("https://fitko-spring.example/it/v1.xsd"))
                .destinationId(nonExistentDestination)
                .build();

        assertThatThrownBy(() -> submissionSender.send(submission))
                .isInstanceOf(SubmissionSendException.class)
                .hasMessageContaining(nonExistentDestination.toString());
    }

    @Test
    void sendingWithNoDestinationIdThrowsBeforeAnyNetworkCall() {
        SubmissionToSend submission = SubmissionToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "fitko-spring IT (sender error)",
                        DataFormat.JSON, "{}", URI.create("https://fitko-spring.example/it/v1.json"))
                .build();

        assertThatThrownBy(() -> submissionSender.send(submission))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destination");
    }

    @Test
    void theSenderBeanIsWired() {
        assertThat(submissionSender).isNotNull();
    }
}
