package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.event.problems.other.TechnicalError;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full Spring context wiring for the receiving side, with the FIT-Connect
 * SDK's {@link SubscriberClient} and the submissions it returns entirely
 * mocked: no real network calls. Automatic polling is disabled ({@code
 * fitconnect.receiver.polling.enabled=false}) so each test triggers exactly
 * one poll cycle deterministically instead of racing a background thread.
 */
@SpringBootTest(classes = ReceivingIntegrationTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.destination-ids=9f6bb611-df46-494a-9a98-a253f1362dc7,2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false"
})
@DirtiesContext
class ReceivingIntegrationTest {

    private static final Path TEMP_DIR = createTempDir();
    private static final Path SIGNING_KEY = TestJwkKeys.writeSigningKey(TEMP_DIR);
    private static final Path DECRYPTION_KEY = TestJwkKeys.writeDecryptionKey(TEMP_DIR);

    @DynamicPropertySource
    static void keyLocations(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.signing-key", () -> "file:" + SIGNING_KEY);
        registry.add("fitconnect.receiver.decryption-keys", () -> "file:" + DECRYPTION_KEY);
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
        RecordingAntragListener recordingAntragListener() {
            return new RecordingAntragListener();
        }
    }

    /** Records every event it sees, and applies whatever outcome the current test configured. */
    static class RecordingAntragListener {

        enum Outcome { NONE, ACCEPT, REJECT }

        final List<ReceivedAntrag> received = new CopyOnWriteArrayList<>();
        volatile Outcome outcome = Outcome.NONE;

        @EventListener
        void onAntrag(AntragReceivedEvent event) {
            received.add(event.getAntrag());
            switch (outcome) {
                case ACCEPT -> event.getAntrag().accept();
                case REJECT -> event.getAntrag().reject(new TechnicalError());
                case NONE -> { /* leave it to the caller */ }
            }
        }
    }

    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID OTHER_DESTINATION_ID = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    @MockitoBean
    SubscriberClient subscriberClient;

    @Autowired
    AntragPollingService pollingService;

    @Autowired
    RecordingAntragListener listener;

    private UUID submissionId;
    private ReceivedSubmission mockSubmission;

    @BeforeEach
    void stubOneAvailableSubmission() {
        submissionId = UUID.randomUUID();
        SubmissionForPickup pickup = new SubmissionForPickup(DESTINATION_ID, submissionId, UUID.randomUUID());
        when(subscriberClient.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(List.of(pickup));
        // Configured as a second destination-id, but has nothing waiting - the
        // fitconnect.receiver.destination-ids list is polled regardless.
        when(subscriberClient.getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockSubmission = mock(ReceivedSubmission.class);
        when(mockSubmission.getSubmissionId()).thenReturn(submissionId);
        when(mockSubmission.getDataAsString()).thenReturn("<test>Hello</test>");
        when(subscriberClient.requestSubmission(submissionId)).thenReturn(mockSubmission);

        listener.outcome = RecordingAntragListener.Outcome.NONE;
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
        listener.outcome = RecordingAntragListener.Outcome.ACCEPT;

        pollingService.poll();

        verify(mockSubmission).acceptSubmission();
    }

    @Test
    void aListenerCanRejectTheSubmission() {
        listener.outcome = RecordingAntragListener.Outcome.REJECT;

        pollingService.poll();

        verify(mockSubmission).rejectSubmission(any());
    }

    @Test
    void pollsEveryConfiguredDestinationInOneCycle() {
        pollingService.poll();

        verify(subscriberClient).getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt());
        verify(subscriberClient).getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt());
    }

    @Test
    void aFailureOnOneDestinationDoesNotStopTheOthersFromBeingPolled() {
        when(subscriberClient.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("simulated network failure"));

        pollingService.poll();

        verify(subscriberClient).getAvailableSubmissionsForDestination(eq(OTHER_DESTINATION_ID), anyInt(), anyInt());
    }
}
