package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.ThrowawayJwks;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListenerFactory;
import com.gfi.ozg.fitko.spring.receive.SubmissionPollingService;
import com.gfi.ozg.fitko.spring.receive.SubmissionProcessor;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestinations;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.client.SubscriberClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-0 - the one integration test that needs no FIT-Connect credentials and
 * runs on every {@code mvn verify}. It consumes {@code fitko-spring} as an
 * external project would - through its published jar and its
 * {@code AutoConfiguration.imports} - and checks the starter is still
 * consumable: the context wires from {@code fitconnect.*} properties alone,
 * the expected beans are present, and the packaging resources ship.
 *
 * <p>The SDK clients are mocked so nothing touches the network.
 */
@Tag("integration")
@Tag("nocreds")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "fitconnect.sender.client-id=dummy-sender",
        "fitconnect.sender.client-secret=dummy-sender-secret",
        "fitconnect.receiver.client-id=dummy-receiver",
        "fitconnect.receiver.client-secret=dummy-receiver-secret",
        "fitconnect.receiver.polling.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StarterConsumabilityIT {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";

    @MockitoBean
    SenderClient senderClient;

    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void throwawayDestination(DynamicPropertyRegistry registry) {
        registry.add("fitconnect.receiver.destinations[0].id", () -> UUID.randomUUID().toString());
        registry.add("fitconnect.receiver.destinations[0].signing-key", ThrowawayJwks::signingKeyResource);
        registry.add("fitconnect.receiver.destinations[0].decryption-keys[0]", ThrowawayJwks::decryptionKeyResource);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubSubscriberClients {
        @Bean
        SubscriberClientFactory subscriberClientFactory() {
            return config -> Mockito.mock(SubscriberClient.class);
        }
    }

    @Test
    void theStarterAutoConfiguresFromPropertiesAlone() {
        assertThat(context.getBean(SubmissionSender.class)).isNotNull();
        assertThat(context.getBean(SubmissionPollingService.class)).isNotNull();
        assertThat(context.getBean(SubmissionProcessor.class)).isNotNull();
        assertThat(context.getBean(SubmissionEventListenerFactory.class)).isNotNull();
        assertThat(context.getBean(ReceivingDestinations.class).all()).hasSize(1);
    }

    @Test
    void theDependencyJarShipsItsAutoConfigurationImports() throws IOException {
        ClassPathResource imports = new ClassPathResource(IMPORTS_RESOURCE);
        assertThat(imports.exists()).as(IMPORTS_RESOURCE + " on the classpath").isTrue();
        String content = new String(imports.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("com.gfi.ozg.fitko.spring.autoconfigure.FitConnectAutoConfiguration")
                .contains("com.gfi.ozg.fitko.spring.autoconfigure.FitConnectSenderAutoConfiguration")
                .contains("com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration");
    }

    @Test
    void theDependencyJarShipsConfigurationMetadataForIdeAutocomplete() throws IOException {
        ClassPathResource metadata = new ClassPathResource(METADATA_RESOURCE);
        assertThat(metadata.exists()).as(METADATA_RESOURCE + " on the classpath").isTrue();
        String content = new String(metadata.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(content).contains("fitconnect.sender.client-id");
    }
}
