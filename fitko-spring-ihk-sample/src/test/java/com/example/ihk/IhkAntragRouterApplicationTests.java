package com.example.ihk;

import com.example.ihk.processstarter.ProcessStarter;
import com.example.ihk.processstarter.ProcessStarterLookup;
import com.example.ihk.processstarter.impl.LoggingProcessStarter;
import com.example.ihk.processstarter.impl.NoopProcessStarter;
import com.example.ihk.routing.AntragRoutingListener;
import com.example.ihk.routing.TenantDirectory;
import com.example.ihk.support.TestJwkKeys;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
import dev.fitko.fitconnect.client.SubscriberClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Full context smoke test: both demo tenants ({@code 101-aachen}, {@code
 * 133-hannover}) wired with real (throwaway) keys, and the SDK's {@link
 * SubscriberClient} mocked out so nothing touches the network. Proves the
 * whole starter plus this project's own routing beans ({@code
 * AntragRoutingProperties}, {@link TenantDirectory}, {@code
 * AntragProcessResolver}, {@link AntragRoutingListener}, {@link
 * ProcessStarterLookup} and both {@link ProcessStarter} implementations)
 * wire up together, the way they would when actually deployed - including
 * {@link ProcessStarterLookup}'s startup-time validation of every class name
 * referenced in {@code application.yaml}'s {@code antrag-routing.*}
 * (the context simply wouldn't start if that failed).
 */
@SpringBootTest(classes = IhkAntragRouterApplication.class, properties = {
        "fitconnect.sender.enabled=false",
        "fitconnect.receiver.client-id=test-client-id",
        "fitconnect.receiver.client-secret=test-client-secret",
        "fitconnect.receiver.polling.enabled=false"
})
@Import(IhkAntragRouterApplicationTests.TestConfig.class)
@DirtiesContext
class IhkAntragRouterApplicationTests {

    private static final Path TEMP_DIR = createTempDir();

    @DynamicPropertySource
    static void destinations(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.id",
                () -> UUID.randomUUID().toString());
        registry.add("fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.signing-key",
                () -> "file:" + TestJwkKeys.writeSigningKey(TEMP_DIR, "aachen-signing.json"));
        registry.add("fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.decryption-keys[0]",
                () -> "file:" + TestJwkKeys.writeDecryptionKey(TEMP_DIR, "aachen-decryption.json"));

        registry.add("fitconnect.receiver.tenants.133-hannover.destinations.antragseingang.id",
                () -> UUID.randomUUID().toString());
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

    @Autowired
    AntragRoutingListener antragRoutingListener;

    @Autowired
    TenantDirectory tenantDirectory;

    @Autowired
    ProcessStarterLookup processStarterLookup;

    @Autowired
    NoopProcessStarter noopProcessStarter;

    @Autowired
    LoggingProcessStarter loggingProcessStarter;

    @Test
    void contextLoadsAndWiresTheRoutingBeans() {
        assertThat(antragRoutingListener).isNotNull();
        assertThat(tenantDirectory).isNotNull();
        // Both implementations coexist as beans - which one runs for a given
        // Antrag is a config-time class-name lookup, not a single injected
        // ProcessStarter (see ProcessStarterLookup).
        assertThat(noopProcessStarter).isNotNull();
        assertThat(loggingProcessStarter).isNotNull();
    }

    @Test
    void lookupResolvesEachConfiguredClassNameToItsMatchingBean() {
        ProcessStarter resolvedNoop = processStarterLookup.resolve(NoopProcessStarter.class.getName());
        ProcessStarter resolvedLogging = processStarterLookup.resolve(LoggingProcessStarter.class.getName());

        assertThat(resolvedNoop).isSameAs(noopProcessStarter);
        assertThat(resolvedLogging).isSameAs(loggingProcessStarter);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> mock(SubscriberClient.class);
        }
    }
}
