package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Verifies {@link SubmissionEventListener}'s per-service filtering against a real
 * Spring context: a listener restricted to one {@code serviceIds} entry only
 * fires for a submission of that service, an unfiltered listener fires for
 * everything, and both run for the same {@link SubmissionReceivedEvent}.
 *
 * <p>{@code SUBSCRIBER_CLIENT} is created statically and handed out via a
 * plain {@code TestConfig}-provided {@link SubscriberClientFactory} bean
 * rather than a {@code @MockitoBean} - {@code FitConnectReceiverAutoConfiguration}
 * calls the factory once while the context is starting, before any {@code
 * @BeforeEach} runs, so a {@code @MockitoBean} stubbed only in {@code
 * @BeforeEach} would still be answering with {@code null} at that point.
 */
@SpringBootTest(classes = SubmissionEventListenerIntegrationTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false"
})
@DirtiesContext
class SubmissionEventListenerIntegrationTest {

    private static final String GEWERBEANMELDUNG = "urn:de:fim:leika:leistung:99050035001000";
    private static final String BAUANTRAG = "urn:de:fim:leika:leistung:99050035002000";
    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");

    private static final SubscriberClient SUBSCRIBER_CLIENT = mock(SubscriberClient.class);

    private static final Path TEMP_DIR = createTempDir();

    @DynamicPropertySource
    static void destination(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.destinations[0].id", DESTINATION_ID::toString);
        registry.add("fitconnect.receiver.destinations[0].signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR));
        registry.add("fitconnect.receiver.destinations[0].decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR));
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
        SelectiveListeners selectiveListeners() {
            return new SelectiveListeners();
        }

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> SUBSCRIBER_CLIENT;
        }
    }

    /** One listener per filtering style, all watching the same event stream. */
    static class SelectiveListeners {

        final List<String> gewerbeanmeldungHits = new CopyOnWriteArrayList<>();
        final List<String> bauantragHits = new CopyOnWriteArrayList<>();
        final List<String> unfilteredHits = new CopyOnWriteArrayList<>();

        @SubmissionEventListener(serviceIds = GEWERBEANMELDUNG)
        void onGewerbeanmeldung(SubmissionReceivedEvent event) {
            gewerbeanmeldungHits.add(event.getSubmission().getSubmissionId().toString());
        }

        @SubmissionEventListener(serviceIds = BAUANTRAG)
        void onBauantrag(SubmissionReceivedEvent event) {
            bauantragHits.add(event.getSubmission().getSubmissionId().toString());
        }

        @SubmissionEventListener
        void onAnySubmission(SubmissionReceivedEvent event) {
            unfilteredHits.add(event.getSubmission().getSubmissionId().toString());
        }
    }

    @Autowired
    SubmissionPollingService pollingService;

    @Autowired
    SelectiveListeners listeners;

    private UUID submissionId;

    @BeforeEach
    void stubOneAvailableGewerbeanmeldung() {
        listeners.gewerbeanmeldungHits.clear();
        listeners.bauantragHits.clear();
        listeners.unfilteredHits.clear();

        reset(SUBSCRIBER_CLIENT);

        submissionId = UUID.randomUUID();
        SubmissionForPickup pickup = new SubmissionForPickup(DESTINATION_ID, submissionId, UUID.randomUUID());
        when(SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(List.of(pickup));

        ReceivedSubmission mockSubmission = mock(ReceivedSubmission.class);
        when(mockSubmission.getSubmissionId()).thenReturn(submissionId);
        when(mockSubmission.getServiceType()).thenReturn(new PublicService("Gewerbeanmeldung", GEWERBEANMELDUNG));
        when(SUBSCRIBER_CLIENT.requestSubmission(submissionId)).thenReturn(mockSubmission);
    }

    @Test
    void aListenerFilteredToTheSubmittedServiceReceivesIt() {
        pollingService.poll();

        assertThat(listeners.gewerbeanmeldungHits).containsExactly(submissionId.toString());
    }

    @Test
    void aListenerFilteredToADifferentServiceDoesNotReceiveIt() {
        pollingService.poll();

        assertThat(listeners.bauantragHits).isEmpty();
    }

    @Test
    void anUnfilteredListenerReceivesEverySubmission() {
        pollingService.poll();

        assertThat(listeners.unfilteredHits).containsExactly(submissionId.toString());
    }
}
