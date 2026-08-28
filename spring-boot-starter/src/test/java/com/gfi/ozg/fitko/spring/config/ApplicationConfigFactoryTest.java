package com.gfi.ozg.fitko.spring.config;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
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
    void buildsAConfigWithOnlyReceivingEnabled() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.getSender().setEnabled(false);
        properties.getReceiver().setClientId("receiver-client-id");
        properties.getReceiver().setClientSecret("receiver-client-secret");
        properties.getReceiver().setSigningKey(new FileSystemResource(TestJwkKeys.writeSigningKey(tempDir)));
        properties.getReceiver().setDecryptionKeys(java.util.List.of(new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir))));

        ApplicationConfig config = ApplicationConfigFactory.create(properties);

        assertThat(config.getSenderConfig()).isNull();
        assertThat(config.getSubscriberConfig().getClientId()).isEqualTo("receiver-client-id");
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
    void rejectsMissingReceiverSigningKey() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.getSender().setEnabled(false);
        properties.getReceiver().setClientId("id");
        properties.getReceiver().setClientSecret("secret");
        properties.getReceiver().setDecryptionKeys(java.util.List.of(new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir))));

        assertThatThrownBy(() -> ApplicationConfigFactory.create(properties))
                .isInstanceOf(FitConnectConfigurationException.class)
                .hasMessageContaining("fitconnect.receiver.signing-key");
    }

    @Test
    void keyResourcesDoNotHaveToBeRealFiles() {
        // Keys are read directly (Resource.getContentAsByteArray()), not via
        // a file path handed to the SDK - an in-memory resource works fine,
        // which matters for e.g. secrets pulled from a vault at startup.
        String signingKeyJson = TestKeyBuilder.generateSignatureKeyPair().getPrivateKey().toJSONString();
        String decryptionKeyJson = TestKeyBuilder.generateEncryptionKeyPair().getPrivateKey().toJSONString();

        FitConnectProperties properties = new FitConnectProperties();
        properties.getSender().setEnabled(false);
        properties.getReceiver().setClientId("id");
        properties.getReceiver().setClientSecret("secret");
        properties.getReceiver().setSigningKey(new ByteArrayResource(signingKeyJson.getBytes(StandardCharsets.UTF_8)));
        properties.getReceiver().setDecryptionKeys(
                java.util.List.of(new ByteArrayResource(decryptionKeyJson.getBytes(StandardCharsets.UTF_8))));

        ApplicationConfig config = ApplicationConfigFactory.create(properties);

        assertThat(config.getSubscriberConfig().getSubscriberKeys().getPrivateSigningKey().toJSONString())
                .isEqualTo(signingKeyJson);
    }

    @Test
    void rejectsAKeyResourceThatIsNotValidJwkJson() {
        FitConnectProperties properties = new FitConnectProperties();
        properties.getSender().setEnabled(false);
        properties.getReceiver().setClientId("id");
        properties.getReceiver().setClientSecret("secret");
        properties.getReceiver().setSigningKey(new ByteArrayResource("not a jwk".getBytes(StandardCharsets.UTF_8)));
        properties.getReceiver().setDecryptionKeys(java.util.List.of(new FileSystemResource(TestJwkKeys.writeDecryptionKey(tempDir))));

        assertThatThrownBy(() -> ApplicationConfigFactory.create(properties))
                .isInstanceOf(FitConnectConfigurationException.class)
                .hasMessageContaining("fitconnect.receiver.signing-key")
                .hasMessageContaining("not a valid JWK");
    }
}
