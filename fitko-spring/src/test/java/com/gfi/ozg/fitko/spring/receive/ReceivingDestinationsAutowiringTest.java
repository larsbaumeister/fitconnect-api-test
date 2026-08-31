package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectAutoConfiguration;
import com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration;
import com.gfi.ozg.fitko.spring.support.TestJwkKeys;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression test for the Spring footgun {@link ReceivingDestinations}
 * exists to avoid: a raw {@code List<ReceivingDestination>} bean is
 * special-cased by Spring's dependency resolution to mean "collect every
 * bean of type {@code ReceivingDestination}" the moment such a bean exists
 * anywhere in the context - silently dropping every configured destination
 * except that stray one, with no error at any point (verified experimentally
 * - see {@link ReceivingDestinations}'s javadoc; also true with the
 * parameter/field name matched to the list bean's name, so name-based
 * injection is not a mitigation either).
 *
 * <p>This context declares a consumer's own unrelated {@code
 * ReceivingDestination} bean alongside the two properly configured
 * destinations - exactly the scenario that used to make {@code
 * List<ReceivingDestination>} injection points silently see only that one
 * stray bean. Asserting {@link SubmissionPollingService#destinationIds()} still
 * lists both configured destinations (not the stray one) proves {@link
 * ReceivingDestinations} closes it.
 */
@SpringBootTest(classes = ReceivingDestinationsAutowiringTest.TestConfig.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false"
})
@DirtiesContext
class ReceivingDestinationsAutowiringTest {

    private static final UUID DESTINATION_ID = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID OTHER_DESTINATION_ID = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");
    private static final UUID STRAY_DESTINATION_ID = UUID.randomUUID();

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
        SubscriberClientFactory subscriberClientFactory() {
            return config -> mock(SubscriberClient.class);
        }

        // The footgun trigger: an application-level bean that happens to be
        // of type ReceivingDestination, entirely unrelated to this starter's
        // own configured list - e.g. a consumer reusing the type for
        // something of their own.
        @Bean
        ReceivingDestination strayReceivingDestination() {
            return new ReceivingDestination(STRAY_DESTINATION_ID, mock(SubscriberClient.class), null);
        }
    }

    @Autowired
    SubmissionPollingService pollingService;

    @Test
    void aStrayReceivingDestinationBeanDoesNotDisplaceTheConfiguredDestinations() {
        List<UUID> destinationIds = pollingService.destinationIds();

        assertThat(destinationIds).containsExactlyInAnyOrder(DESTINATION_ID, OTHER_DESTINATION_ID);
        assertThat(destinationIds).doesNotContain(STRAY_DESTINATION_ID);
    }
}
