package com.gfi.ozg.fitko.spring.receive;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end (real Spring context, SDK {@link SubscriberClient} mocked) check
 * that one destination's poll page is processed <em>in parallel</em> through
 * the full wiring - {@code SubmissionPollingService} ->
 * {@link SafeguardedSubmissionRunner} -> listeners - with {@code
 * fitconnect.receiver.polling.concurrency=4}, and that {@code poll()} still
 * blocks until the whole page is done.
 */
@SpringBootTest(classes = ParallelPollingIntegrationTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false",
        "fitconnect.receiver.polling.concurrency=4"
})
@DirtiesContext
class ParallelPollingIntegrationTest {

    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final int CONCURRENCY = 4;
    private static final int PAGE_SIZE = 12;
    private static final Duration HANDLER_WORK = Duration.ofMillis(150);

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
        ConcurrencyRecordingListener concurrencyRecordingListener() {
            return new ConcurrencyRecordingListener();
        }

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> SUBSCRIBER_CLIENT;
        }
    }

    /** Each invocation overlaps with others for {@link #HANDLER_WORK}; records the peak overlap and every id handled. */
    static class ConcurrencyRecordingListener {

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<UUID> handled = new CopyOnWriteArrayList<>();

        @EventListener
        void onSubmission(SubmissionReceivedEvent event) throws InterruptedException {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(HANDLER_WORK.toMillis());
                handled.add(event.getSubmission().getSubmissionId());
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    @Autowired
    SubmissionPollingService pollingService;

    @Autowired
    ConcurrencyRecordingListener listener;

    private List<UUID> pageSubmissionIds;

    @BeforeEach
    void stubAFullPage() {
        reset(SUBSCRIBER_CLIENT);
        listener.inFlight.set(0);
        listener.maxInFlight.set(0);
        listener.handled.clear();

        pageSubmissionIds = new ArrayList<>();
        List<SubmissionForPickup> pickups = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE; i++) {
            UUID submissionId = UUID.randomUUID();
            pageSubmissionIds.add(submissionId);
            pickups.add(new SubmissionForPickup(DESTINATION_ID, submissionId, UUID.randomUUID()));

            ReceivedSubmission submission = mock(ReceivedSubmission.class);
            when(submission.getSubmissionId()).thenReturn(submissionId);
            when(submission.getDataAsString()).thenReturn("<test>Hello</test>");
            when(SUBSCRIBER_CLIENT.requestSubmission(submissionId)).thenReturn(submission);
        }
        when(SUBSCRIBER_CLIENT.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), anyInt(), anyInt()))
                .thenReturn(pickups);
    }

    @Test
    void processesOnePageInParallelAndBlocksUntilItIsDone() {
        Instant start = Instant.now();

        pollingService.poll();

        Duration elapsed = Duration.between(start, Instant.now());

        // poll() returned only after the whole page finished...
        assertThat(listener.handled).containsExactlyInAnyOrderElementsOf(pageSubmissionIds);
        // ...processed several at a time (up to concurrency), not one by one...
        assertThat(listener.maxInFlight.get()).isBetween(2, CONCURRENCY);
        // ...so it took roughly ceil(12/4) * 150ms, well under a sequential 12 * 150ms.
        assertThat(elapsed).isLessThan(HANDLER_WORK.multipliedBy(PAGE_SIZE));
    }
}
