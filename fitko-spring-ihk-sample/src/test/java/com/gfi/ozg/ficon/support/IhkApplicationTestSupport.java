package com.gfi.ozg.ficon.support;

import com.gfi.ozg.ficon.IhkAntragRouterApplication;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
import dev.fitko.fitconnect.client.SubscriberClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * Shared full-context setup: both demo tenants ({@code 101-aachen}, {@code
 * 133-hannover}) wired with real (throwaway) keys, the SDK's {@link
 * SubscriberClient} mocked out so nothing touches the network, polling and
 * the scheduled dispatcher switched off (tests trigger both directly), and
 * the inbox on an in-memory H2 database.
 */
@SpringBootTest(classes = IhkAntragRouterApplication.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false",
        "antrag-dispatch.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ihk-inbox-test;DB_CLOSE_DELAY=-1"
})
@Import(IhkApplicationTestSupport.MockSubscriberClientConfig.class)
@DirtiesContext
public abstract class IhkApplicationTestSupport {

    protected static final UUID AACHEN_DESTINATION = UUID.randomUUID();
    protected static final UUID HANNOVER_DESTINATION = UUID.randomUUID();

    private static final Path TEMP_DIR = createTempDir();

    @DynamicPropertySource
    static void destinations(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.id",
                AACHEN_DESTINATION::toString);
        registry.add("fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR, "aachen-signing.json"));
        registry.add("fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR, "aachen-decryption.json"));

        registry.add("fitconnect.receiver.tenants.133-hannover.destinations.antragseingang.id",
                HANNOVER_DESTINATION::toString);
        registry.add("fitconnect.receiver.tenants.133-hannover.destinations.antragseingang.signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR, "hannover-signing.json"));
        registry.add("fitconnect.receiver.tenants.133-hannover.destinations.antragseingang.decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR, "hannover-decryption.json"));
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("fitko-spring-ihk-sample-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockSubscriberClientConfig {

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> mock(SubscriberClient.class);
        }
    }
}
