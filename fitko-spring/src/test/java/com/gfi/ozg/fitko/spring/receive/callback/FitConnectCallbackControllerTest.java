package com.gfi.ozg.fitko.spring.receive.callback;

import dev.fitko.fitconnect.api.domain.model.callback.CallbackHeaderKeys;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.api.domain.validation.ValidationResult;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectAutoConfiguration;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectCallbackAutoConfiguration;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
import com.gfi.ozg.fitko.spring.support.TestJwkKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@link FitConnectCallbackController} through real HTTP dispatch
 * ({@link MockMvc}), with {@link SubscriberClient#validateCallback} mocked
 * rather than computing real HMACs - that's the SDK's own concern (already
 * unit-tested there); what matters here is that the controller calls it with
 * the right arguments and honours the result. Polling is disabled so only
 * the callback path is under test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = FitConnectCallbackControllerTest.TestConfig.class,
        properties = {
                "fitconnect.sender.enabled=false",
                "fitconnect.receiver.client-id=test-client-id",
                "fitconnect.receiver.client-secret=test-client-secret",
                "fitconnect.receiver.polling.enabled=false",
                "fitconnect.receiver.callback.enabled=true"
        })
@AutoConfigureMockMvc
@DirtiesContext
class FitConnectCallbackControllerTest {

    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID UNCONFIGURED_DESTINATION_ID = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");
    private static final String CALLBACK_SECRET = "test-callback-secret";

    private static final SubscriberClient SUBSCRIBER_CLIENT = mock(SubscriberClient.class);

    private static final Path TEMP_DIR = createTempDir();

    @DynamicPropertySource
    static void destination(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.destinations[0].id", DESTINATION_ID::toString);
        registry.add("fitconnect.receiver.destinations[0].signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR));
        registry.add("fitconnect.receiver.destinations[0].decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR));
        registry.add("fitconnect.receiver.destinations[0].callback-secret", () -> CALLBACK_SECRET);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("fitconnect-spring-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            FitConnectAutoConfiguration.class,
            FitConnectReceiverAutoConfiguration.class,
            FitConnectCallbackAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> SUBSCRIBER_CLIENT;
        }
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void resetMock() {
        reset(SUBSCRIBER_CLIENT);
    }

    @Test
    void acceptsAValidCallbackAndDownloadsTheListedSubmission() throws Exception {
        UUID submissionId = UUID.randomUUID();
        stubValidHmac();
        when(SUBSCRIBER_CLIENT.requestSubmission(submissionId)).thenReturn(mock(ReceivedSubmission.class));

        mockMvc.perform(post("/fitconnect/callback/" + DESTINATION_ID)
                        .header(CallbackHeaderKeys.AUTHENTICATION, "valid-hmac")
                        .header(CallbackHeaderKeys.TIMESTAMP, "1700000000")
                        .contentType("application/json")
                        .content(newSubmissionsBody(DESTINATION_ID, submissionId)))
                .andExpect(status().isOk());

        verify(SUBSCRIBER_CLIENT).requestSubmission(submissionId);
    }

    @Test
    void rejectsACallbackWithAnInvalidHmac() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(SUBSCRIBER_CLIENT.validateCallback(eq("bad-hmac"), anyLong(), any(), eq(CALLBACK_SECRET)))
                .thenReturn(ValidationResult.error("hmac mismatch"));

        mockMvc.perform(post("/fitconnect/callback/" + DESTINATION_ID)
                        .header(CallbackHeaderKeys.AUTHENTICATION, "bad-hmac")
                        .header(CallbackHeaderKeys.TIMESTAMP, "1700000000")
                        .contentType("application/json")
                        .content(newSubmissionsBody(DESTINATION_ID, submissionId)))
                .andExpect(status().isUnauthorized());

        verify(SUBSCRIBER_CLIENT, never()).requestSubmission(any(UUID.class));
    }

    @Test
    void rejectsACallbackForAnUnconfiguredDestination() throws Exception {
        mockMvc.perform(post("/fitconnect/callback/" + UNCONFIGURED_DESTINATION_ID)
                        .header(CallbackHeaderKeys.AUTHENTICATION, "irrelevant")
                        .header(CallbackHeaderKeys.TIMESTAMP, "1700000000")
                        .contentType("application/json")
                        .content(newSubmissionsBody(UNCONFIGURED_DESTINATION_ID, UUID.randomUUID())))
                .andExpect(status().isNotFound());

        verify(SUBSCRIBER_CLIENT, never()).validateCallback(any(), any(), any(), any());
    }

    @Test
    void ignoresASubmissionListedForADifferentDestinationThanThePathVariable() throws Exception {
        UUID submissionForAnotherDestination = UUID.randomUUID();
        stubValidHmac();

        mockMvc.perform(post("/fitconnect/callback/" + DESTINATION_ID)
                        .header(CallbackHeaderKeys.AUTHENTICATION, "valid-hmac")
                        .header(CallbackHeaderKeys.TIMESTAMP, "1700000000")
                        .contentType("application/json")
                        .content(newSubmissionsBody(UNCONFIGURED_DESTINATION_ID, submissionForAnotherDestination)))
                .andExpect(status().isOk());

        verify(SUBSCRIBER_CLIENT, never()).requestSubmission(submissionForAnotherDestination);
    }

    private static void stubValidHmac() {
        when(SUBSCRIBER_CLIENT.validateCallback(eq("valid-hmac"), anyLong(), any(), eq(CALLBACK_SECRET)))
                .thenReturn(ValidationResult.ok());
    }

    private static String newSubmissionsBody(UUID destinationId, UUID submissionId) {
        return """
                {"type":"new-submissions","submissions":[{"destinationId":"%s","submissionId":"%s","caseId":"%s"}]}
                """.formatted(destinationId, submissionId, UUID.randomUUID());
    }
}
