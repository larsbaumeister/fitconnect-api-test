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
import org.springframework.context.event.EventListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code fitconnect.receiver.polling.submission-timeout}: a listener
 * that never returns must not stall the rest of the poll cycle. Uses a real
 * Spring context (like {@link ReceivingIntegrationTest}) with the {@link
 * SubscriberClient} mocked and a short (100ms) timeout so the slow listener's
 * sleep (well beyond it) still keeps the test itself fast.
 */
@SpringBootTest(classes = SubmissionTimeoutIntegrationTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false",
        "fitconnect.receiver.polling.submission-timeout=100ms"
})
@DirtiesContext
class SubmissionTimeoutIntegrationTest {

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
        SlowingSubmissionListener slowingSubmissionListener() {
            return new SlowingSubmissionListener();
        }

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> SUBSCRIBER_CLIENT;
        }
    }

    /** Sleeps well past the configured timeout for one designated submission id; records every id it actually finishes handling. */
    static class SlowingSubmissionListener {

        volatile UUID slowSubmissionId;
        final List<UUID> handled = new CopyOnWriteArrayList<>();

        @EventListener
        void onSubmission(SubmissionReceivedEvent event) throws InterruptedException {
            UUID submissionId = event.getSubmission().getSubmissionId();
            if (submissionId.equals(slowSubmissionId)) {
                Thread.sleep(2000); // far beyond the 100ms submission-timeout configured above
            }
            handled.add(submissionId);
        }
    }

    @Autowired
    SubmissionPollingService pollingService;

    @Autowired
    SlowingSubmissionListener listener;

    private UUID slowSubmissionId;
    private UUID fastSubmissionId;

    @BeforeEach
    void stubTwoAvailableSubmissions() {
        reset(SUBSCRIBER_CLIENT);
        slowSubmissionId = UUID.randomUUID();
        fastSubmissionId = UUID.randomUUID();
        listener.slowSubmissionId = slowSubmissionId;
        listener.handled.clear();

        List<SubmissionForPickup> pickups = List.of(
                new SubmissionForPickup(DESTINATION_ID, slowSubmissionId, UUID.randomUUID()),
                new SubmissionForPickup(DESTINATION_ID, fastSubmissionId, UUID.randomUUID()));
        when(SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(pickups);

        // Built before when(...) below: calling when() while another mock's
        // own stubbing is still "open" (as stubbedSubmission(...) inline as
        // a .thenReturn() argument would) confuses Mockito's thread-local
        // stubbing state.
        ReceivedSubmission slowSubmission = stubbedSubmission(slowSubmissionId);
        ReceivedSubmission fastSubmission = stubbedSubmission(fastSubmissionId);
        when(SUBSCRIBER_CLIENT.requestSubmission(slowSubmissionId)).thenReturn(slowSubmission);
        when(SUBSCRIBER_CLIENT.requestSubmission(fastSubmissionId)).thenReturn(fastSubmission);
    }

    private static ReceivedSubmission stubbedSubmission(UUID submissionId) {
        ReceivedSubmission submission = mock(ReceivedSubmission.class);
        when(submission.getSubmissionId()).thenReturn(submissionId);
        when(submission.getDataAsString()).thenReturn("<test>Hello</test>");
        return submission;
    }

    @Test
    void aSlowSubmissionIsAbandonedInsteadOfStallingTheRestOfTheCycle() {
        Instant start = Instant.now();

        pollingService.poll();

        // Well under the listener's 2s sleep: proves poll() didn't wait for it.
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(1));
        // The second, well-behaved submission in the same cycle still got processed.
        assertThat(listener.handled).contains(fastSubmissionId);
    }
}
