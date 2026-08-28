package com.gfi.ozg.fitko.spring.config;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.support.TestJwkKeys;
import dev.fitko.fitconnect.tools.keygen.TestKeyBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationConfigFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsAConfigWithOnlySendingEnabled() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.setEnvironment("TEST");
        properties.getReceiver().setEnabled(false);
        properties.getSender().setClientId("sender-client-id");
        properties.getSender().setClientSecret("sender-client-secret");

        ApplicationConfig config = ApplicationConfigFactory.create(properties);

        assertThat(config.getSenderConfig().getClientId()).isEqualTo("sender-client-id");
        assertThat(config.getSubscriberConfig()).isNull();
        assertThat(config.getActiveEnvironment().getName()).isEqualTo("TEST");
    }

    @Test
    void neverAttachesASubscriberConfigRegardlessOfReceiverSettings() {
        // create() only builds what's shared across the whole application;
        // a destination's SubscriberConfig is assembled separately (see
        // createSubscriberConfig/withSubscriberConfig below) since different
        // destinations can have entirely different keys.
        FitConnectProperties properties = new FitConnectProperties();
        properties.getSender().setEnabled(false);
        properties.getReceiver().setClientId("receiver-client-id");
        properties.getReceiver().setClientSecret("receiver-client-secret");

        ApplicationConfig config = ApplicationConfigFactory.create(properties);

        assertThat(config.getSenderConfig()).isNull();
        assertThat(config.getSubscriberConfig()).isNull();
    }

    @Test
    void appliesBaseUrlOverrides() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.getReceiver().setEnabled(false);
        properties.getSender().setClientId("id");
        properties.getSender().setClientSecret("secret");
        properties.getBaseUrls().setAuth("http://localhost:1234/auth");

        ApplicationConfig config = ApplicationConfigFactory.create(properties);

        assertThat(config.getAuthBaseUrl()).isEqualTo("http://localhost:1234/auth");
    }

    @Test
    void rejectsMissingSenderClientId() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.getReceiver().setEnabled(false);
        properties.getSender().setClientSecret("secret");

        assertThatThrownBy(() -> ApplicationConfigFactory.create(properties))
                .isInstanceOf(FitConnectConfigurationException.class)
                .hasMessageContaining("fitconnect.sender.client-id");
    }

    @Test
    void withSubscriberConfigKeepsEverythingElseFromTheBaseConfig() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.getReceiver().setEnabled(false);
        properties.getSender().setClientId("id");
        properties.getSender().setClientSecret("secret");
        properties.getBaseUrls().setAuth("http://localhost:1234/auth");
        ApplicationConfig base = ApplicationConfigFactory.create(properties);

        SubscriberConfig subscriberConfig = subscriberConfig("receiver-client-id");
        ApplicationConfig withSubscriber = ApplicationConfigFactory.withSubscriberConfig(base, subscriberConfig);

        assertThat(withSubscriber.getSubscriberConfig()).isSameAs(subscriberConfig);
        assertThat(withSubscriber.getSenderConfig()).isEqualTo(base.getSenderConfig());
        assertThat(withSubscriber.getAuthBaseUrl()).isEqualTo("http://localhost:1234/auth");
    }

    @Test
    void createSubscriberConfigUsesTheDestinationsOwnCredentialsWhenSet() {
        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId("shared-client-id");
        receiver.setClientSecret("shared-client-secret");

        FitConnectProperties.Receiver.Destination destination = destination();
        destination.setClientId("destination-specific-client-id");
        destination.setClientSecret("destination-specific-client-secret");

        SubscriberConfig config = ApplicationConfigFactory.createSubscriberConfig(receiver, destination);

        assertThat(config.getClientId()).isEqualTo("destination-specific-client-id");
        assertThat(config.getClientSecret()).isEqualTo("destination-specific-client-secret");
    }

    @Test
    void createSubscriberConfigFallsBackToTheReceiversCredentialsWhenTheDestinationDoesNotSetItsOwn() {
        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId("shared-client-id");
        receiver.setClientSecret("shared-client-secret");

        SubscriberConfig config = ApplicationConfigFactory.createSubscriberConfig(receiver, destination());

        assertThat(config.getClientId()).isEqualTo("shared-client-id");
        assertThat(config.getClientSecret()).isEqualTo("shared-client-secret");
    }

    @Test
    void twoDestinationsCanUseCompletelyDifferentKeys() {
        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId("shared-client-id");
        receiver.setClientSecret("shared-client-secret");

        FitConnectProperties.Receiver.Destination destinationA = new FitConnectProperties.Receiver.Destination();
        destinationA.setId(UUID.randomUUID());
        destinationA.setSigningKey(new FileSystemResource(TestJwkKeys.writeSigningKey(tempDir, "a-signing.json")));
        destinationA.setDecryptionKeys(java.util.List.of(
                new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir, "a-decryption.json"))));

        FitConnectProperties.Receiver.Destination destinationB = new FitConnectProperties.Receiver.Destination();
        destinationB.setId(UUID.randomUUID());
        destinationB.setSigningKey(new FileSystemResource(TestJwkKeys.writeSigningKey(tempDir, "b-signing.json")));
        destinationB.setDecryptionKeys(java.util.List.of(
                new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir, "b-decryption.json"))));

        SubscriberConfig configA = ApplicationConfigFactory.createSubscriberConfig(receiver, destinationA);
        SubscriberConfig configB = ApplicationConfigFactory.createSubscriberConfig(receiver, destinationB);

        assertThat(configA.getSubscriberKeys().getPrivateSigningKey())
                .isNotEqualTo(configB.getSubscriberKeys().getPrivateSigningKey());
    }

    @Test
    void rejectsMissingReceiverSigningKey() {
        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId("id");
        receiver.setClientSecret("secret");

        FitConnectProperties.Receiver.Destination destination = new FitConnectProperties.Receiver.Destination();
        destination.setId(UUID.randomUUID());
        destination.setDecryptionKeys(java.util.List.of(new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir))));

        assertThatThrownBy(() -> ApplicationConfigFactory.createSubscriberConfig(receiver, destination))
                .isInstanceOf(FitConnectConfigurationException.class)
                .hasMessageContaining("signing-key");
    }

    @Test
    void keyResourcesDoNotHaveToBeRealFiles() {
        // Keys are read directly (Resource.getContentAsByteArray()), not via
        // a file path handed to the SDK - an in-memory resource works fine,
        // which matters for e.g. secrets pulled from a vault at startup.
        String signingKeyJson = TestKeyBuilder.generateSignatureKeyPair().getPrivateKey().toJSONString();
        String decryptionKeyJson = TestKeyBuilder.generateEncryptionKeyPair().getPrivateKey().toJSONString();

        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId("id");
        receiver.setClientSecret("secret");

        FitConnectProperties.Receiver.Destination destination = new FitConnectProperties.Receiver.Destination();
        destination.setId(UUID.randomUUID());
        destination.setSigningKey(new ByteArrayResource(signingKeyJson.getBytes(StandardCharsets.UTF_8)));
        destination.setDecryptionKeys(
                java.util.List.of(new ByteArrayResource(decryptionKeyJson.getBytes(StandardCharsets.UTF_8))));

        SubscriberConfig config = ApplicationConfigFactory.createSubscriberConfig(receiver, destination);

        assertThat(config.getSubscriberKeys().getPrivateSigningKey().toJSONString()).isEqualTo(signingKeyJson);
    }

    @Test
    void rejectsAKeyResourceThatIsNotValidJwkJson() {
        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId("id");
        receiver.setClientSecret("secret");

        FitConnectProperties.Receiver.Destination destination = new FitConnectProperties.Receiver.Destination();
        destination.setId(UUID.randomUUID());
        destination.setSigningKey(new ByteArrayResource("not a jwk".getBytes(StandardCharsets.UTF_8)));
        destination.setDecryptionKeys(java.util.List.of(new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir))));

        assertThatThrownBy(() -> ApplicationConfigFactory.createSubscriberConfig(receiver, destination))
                .isInstanceOf(FitConnectConfigurationException.class)
                .hasMessageContaining("signing-key")
                .hasMessageContaining("not a valid JWK");
    }

    private FitConnectProperties.Receiver.Destination destination() {
        FitConnectProperties.Receiver.Destination destination = new FitConnectProperties.Receiver.Destination();
        destination.setId(UUID.randomUUID());
        destination.setSigningKey(new FileSystemResource(TestJwkKeys.writeSigningKey(tempDir)));
        destination.setDecryptionKeys(java.util.List.of(new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir))));
        return destination;
    }

    private SubscriberConfig subscriberConfig(String clientId) {
        FitConnectProperties.Receiver receiver = new FitConnectProperties.Receiver();
        receiver.setClientId(clientId);
        receiver.setClientSecret("secret");
        return ApplicationConfigFactory.createSubscriberConfig(receiver, destination());
    }
}
