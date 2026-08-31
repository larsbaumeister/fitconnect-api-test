package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectAutoConfiguration;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration;
import com.gfi.ozg.fitko.spring.support.TestJwkKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code fitconnect.receiver.polling.retry-cooldown}: a submission
 * that fails is not re-fetched on the very next cycle, but is retried once
 * the cooldown elapses; a submission that succeeds is never held back by it.
 * Configured with a short (150ms) cooldown so the elapsed-case can be tested
 * with a real (short) wait instead of mocking time.
 */
@SpringBootTest(classes = SubmissionRetryCooldownIntegrationTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false",
        "fitconnect.receiver.polling.retry-cooldown=150ms"
})
@DirtiesContext
class SubmissionRetryCooldownIntegrationTest {

    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final SubscriberClient SUBSCRIBER_CLIENT = mock(SubscriberClient.class);
    private static final Path TEMP_DIR = createTempDir();

    @DynamicPropertySource
    static void destination(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.destinations[0].id", DESTINATION_ID::toString);
        registry.add("fitconnect.receiver.destinations[0].signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR, "signing.json"));
        registry.add("fitconnect.receiver.destinations[0].decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR, "decryption.json"));
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("fitconnect-spring-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({FitConnectAutoConfiguration.class, FitConnectReceiverAutoConfiguration.class})
    static class TestConfig {

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> SUBSCRIBER_CLIENT;
        }
    }

    @Autowired
    SubmissionPollingService pollingService;

    private UUID submissionId;

    @BeforeEach
    void stubOneAvailableSubmission() {
        reset(SUBSCRIBER_CLIENT);
        submissionId = UUID.randomUUID();
        SubmissionForPickup pickup = new SubmissionForPickup(DESTINATION_ID, submissionId, UUID.randomUUID());
        when(SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(List.of(pickup));
    }

    @Test
    void aFailedSubmissionIsNotRetriedUntilTheCooldownElapses() throws InterruptedException {
        when(SUBSCRIBER_CLIENT.requestSubmission(submissionId))
                .thenThrow(new RuntimeException("simulated persistently bad submission"));

        pollingService.poll();
        verify(SUBSCRIBER_CLIENT, times(1)).requestSubmission(submissionId);

        // Immediately retried: still within the 150ms cooldown, so no second fetch.
        pollingService.poll();
        verify(SUBSCRIBER_CLIENT, times(1)).requestSubmission(submissionId);

        // Wait out the cooldown, then it's fair game again.
        Thread.sleep(200);
        pollingService.poll();
        verify(SUBSCRIBER_CLIENT, times(2)).requestSubmission(submissionId);
    }

    @Test
    void aSuccessfulSubmissionIsNeverHeldBackByTheCooldown() {
        ReceivedSubmission submission = mock(ReceivedSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getDataAsString()).thenReturn("<test>Hello</test>");
        when(SUBSCRIBER_CLIENT.requestSubmission(submissionId)).thenReturn(submission);

        pollingService.poll();
        pollingService.poll();

        // No failure was ever recorded for this submission, so both cycles fetched it.
        verify(SUBSCRIBER_CLIENT, times(2)).requestSubmission(submissionId);
    }

    @Test
    void aSubmissionThatStartsFailingThenSucceedsClearsItsCooldownImmediately() throws InterruptedException {
        // Mockito re-stubs a mock by literally invoking it, which would trigger
        // an already-configured throw - so the throw-then-succeed sequence has
        // to be chained up front instead of re-stubbed mid-test.
        ReceivedSubmission submission = mock(ReceivedSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getDataAsString()).thenReturn("<test>Hello</test>");
        when(SUBSCRIBER_CLIENT.requestSubmission(submissionId))
                .thenThrow(new RuntimeException("simulated transient failure"))
                .thenReturn(submission);

        pollingService.poll();
        verify(SUBSCRIBER_CLIENT, times(1)).requestSubmission(submissionId);

        Thread.sleep(200);
        pollingService.poll();
        verify(SUBSCRIBER_CLIENT, times(2)).requestSubmission(submissionId);

        // Succeeded: cooldown state is cleared, so the very next cycle fetches it again too.
        pollingService.poll();
        verify(SUBSCRIBER_CLIENT, times(3)).requestSubmission(submissionId);
    }
}
