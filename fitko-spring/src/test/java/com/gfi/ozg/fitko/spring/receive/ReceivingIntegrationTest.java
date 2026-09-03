package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.event.problems.other.TechnicalError;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectAutoConfiguration;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full Spring context wiring for the receiving side, with the FIT-Connect
 * SDK's {@link SubscriberClient} and the submissions it returns entirely
 * mocked: no real network calls. Automatic polling is disabled ({@code
 * fitconnect.receiver.polling.enabled=false}) so each test triggers exactly
 * one poll cycle deterministically instead of racing a background thread.
 *
 * <p>The two configured destinations use genuinely different signing/
 * decryption keys ({@code fitconnect.receiver.destinations[*].signing-key}/
 * {@code decryption-keys}), and {@code TestConfig} gives each its own {@link
 * SubscriberClient} mock via {@link SubscriberClientFactory} - exercising
 * exactly the wiring {@link FitConnectReceiverAutoConfiguration} does in
 * production: one real {@code SubscriberClient} per destination, not one
 * shared client. The two mocks are created statically (not via {@code
 * @MockitoBean}) because {@code FitConnectReceiverAutoConfiguration} calls
 * the factory once while the context is starting, before any {@code
 * @BeforeEach} runs - a {@code @MockitoBean} only stubbed later would still
 * be answering with {@code null} at that point.
 */
@SpringBootTest(classes = ReceivingIntegrationTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false"
})
@DirtiesContext
class ReceivingIntegrationTest {

    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID OTHER_DESTINATION_ID = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    private static final SubscriberClient SUBSCRIBER_CLIENT = mock(SubscriberClient.class);
    private static final SubscriberClient OTHER_SUBSCRIBER_CLIENT = mock(SubscriberClient.class);

    private static final Path TEMP_DIR = createTempDir();

    @DynamicPropertySource
    static void destinations(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.destinations[0].id", DESTINATION_ID::toString);
        registry.add("fitconnect.receiver.destinations[0].signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR, "a-signing.json"));
        registry.add("fitconnect.receiver.destinations[0].decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR, "a-decryption.json"));

        registry.add("fitconnect.receiver.destinations[1].id", OTHER_DESTINATION_ID::toString);
        registry.add("fitconnect.receiver.destinations[1].signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR, "b-signing.json"));
        registry.add("fitconnect.receiver.destinations[1].decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR, "b-decryption.json"));
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
        RecordingSubmissionListener recordingSubmissionListener() {
            return new RecordingSubmissionListener();
        }

        // FitConnectReceiverAutoConfiguration calls this once per configured
        // destination, in list order: destinations[0] gets SUBSCRIBER_CLIENT,
        // destinations[1] gets OTHER_SUBSCRIBER_CLIENT.
        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            Deque<SubscriberClient> clientsInConfiguredOrder =
                    new ArrayDeque<>(List.of(SUBSCRIBER_CLIENT, OTHER_SUBSCRIBER_CLIENT));
            return config -> clientsInConfiguredOrder.remove();
        }
    }

    /** Records every event it sees, and applies whatever outcome the current test configured. */
    static class RecordingSubmissionListener {

        enum Outcome { NONE, ACCEPT, REJECT }

        final List<IncomingSubmission> received = new CopyOnWriteArrayList<>();
        volatile Outcome outcome = Outcome.NONE;

        @EventListener
        void onSubmission(SubmissionReceivedEvent event) {
            received.add(event.getSubmission());
            switch (outcome) {
                case ACCEPT -> event.getSubmission().accept();
                case REJECT -> event.getSubmission().reject(new TechnicalError());
                case NONE -> { /* leave it to the caller */ }
            }
        }
    }

    @Autowired
    SubmissionPollingService pollingService;

    @Autowired
    RecordingSubmissionListener listener;

    private UUID submissionId;
    private ReceivedSubmission mockSubmission;

    @BeforeEach
    void stubOneAvailableSubmission() {
        reset(SUBSCRIBER_CLIENT, OTHER_SUBSCRIBER_CLIENT);

        submissionId = UUID.randomUUID();
        SubmissionForPickup pickup = new SubmissionForPickup(DESTINATION_ID, submissionId, UUID.randomUUID());
        when(SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(List.of(pickup));
        // Configured as a second destination, but has nothing waiting - the
        // fitconnect.receiver.destinations list is polled regardless.
        when(OTHER_SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockSubmission = mock(ReceivedSubmission.class);
        when(mockSubmission.getSubmissionId()).thenReturn(submissionId);
        when(mockSubmission.getDataAsString()).thenReturn("<test>Hello</test>");
        when(SUBSCRIBER_CLIENT.requestSubmission(submissionId)).thenReturn(mockSubmission);

        listener.outcome = RecordingSubmissionListener.Outcome.NONE;
        listener.received.clear();
    }

    @Test
    void publishesAnEventCarryingTheDownloadedSubmission() {
        pollingService.poll();

        assertThat(listener.received).hasSize(1);
        assertThat(listener.received.get(0).getSubmissionId()).isEqualTo(submissionId);
        assertThat(listener.received.get(0).getDataAsString()).isEqualTo("<test>Hello</test>");
    }

    @Test
    void leavesTheSubmissionOnTheDeliveryServiceByDefault() {
        pollingService.poll();

        verify(mockSubmission, never()).acceptSubmission();
        verify(mockSubmission, never()).rejectSubmission(any());
    }

    @Test
    void aListenerCanAcceptTheSubmission() {
        listener.outcome = RecordingSubmissionListener.Outcome.ACCEPT;

        pollingService.poll();

        verify(mockSubmission).acceptSubmission();
    }

    @Test
    void aListenerCanRejectTheSubmission() {
        listener.outcome = RecordingSubmissionListener.Outcome.REJECT;

        pollingService.poll();

        verify(mockSubmission).rejectSubmission(any());
    }

    @Test
    void pollsEveryConfiguredDestinationInOneCycle() {
        pollingService.poll();

        verify(SUBSCRIBER_CLIENT).getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt());
        verify(OTHER_SUBSCRIBER_CLIENT).getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt());
    }

    @Test
    void eachDestinationIsPolledThroughItsOwnSubscriberClient() {
        // The two destinations were given different keys - if the wrong
        // client were used for a destination in production, decryption
        // would fail. Here it shows up simply as: destinations[1]'s pickup
        // call must never reach destinations[0]'s mock, and vice versa.
        pollingService.poll();

        verify(SUBSCRIBER_CLIENT, never()).getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt());
        verify(OTHER_SUBSCRIBER_CLIENT, never()).getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt());
    }

    @Test
    void aFailureOnOneDestinationDoesNotStopTheOthersFromBeingPolled() {
        when(SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("simulated network failure"));

        pollingService.poll();

        verify(OTHER_SUBSCRIBER_CLIENT).getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt());
    }
}
