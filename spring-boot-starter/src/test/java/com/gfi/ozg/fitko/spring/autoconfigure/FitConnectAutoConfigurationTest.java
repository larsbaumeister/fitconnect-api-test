package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.client.SubscriberClient;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.receive.AntragPollingService;
import com.gfi.ozg.fitko.spring.send.AntragSender;
import com.gfi.ozg.fitko.spring.support.TestJwkKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the auto-configuration's conditionals in isolation, without a
 * full Spring Boot application - no real network or filesystem access beyond
 * the throwaway JWKs {@link TestJwkKeys} writes into {@code @TempDir}.
 */
class FitConnectAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FitConnectAutoConfiguration.class,
                    FitConnectSenderAutoConfiguration.class,
                    FitConnectReceiverAutoConfiguration.class));

    @Test
    void backsOffEntirelyWhenDisabled() {
        contextRunner.withPropertyValues("fitconnect.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AntragSender.class)
                        .doesNotHaveBean(AntragPollingService.class));
    }

    @Test
    void wiresOnlyTheSenderSideWhenReceiverIsDisabled() {
        contextRunner.withPropertyValues(
                        "fitconnect.receiver.enabled=false",
                        "fitconnect.sender.client-id=id",
                        "fitconnect.sender.client-secret=secret")
                .run(context -> assertThat(context)
                        .hasSingleBean(SenderClient.class)
                        .hasSingleBean(AntragSender.class)
                        .doesNotHaveBean(SubscriberClient.class)
                        .doesNotHaveBean(AntragPollingService.class));
    }

    @Test
    void wiresOnlyTheReceiverSideWhenSenderIsDisabled() {
        Path signingKey = TestJwkKeys.writeSigningKey(tempDir);
        Path decryptionKey = TestJwkKeys.writeDecryptionKey(tempDir);

        contextRunner.withPropertyValues(
                        "fitconnect.sender.enabled=false",
                        "fitconnect.receiver.destination-ids=9f6bb611-df46-494a-9a98-a253f1362dc7,2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234",
                        "fitconnect.receiver.client-id=id",
                        "fitconnect.receiver.client-secret=secret",
                        "fitconnect.receiver.signing-key=file:" + signingKey,
                        "fitconnect.receiver.decryption-keys=file:" + decryptionKey,
                        "fitconnect.receiver.polling.enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(SubscriberClient.class)
                        .hasSingleBean(AntragPollingService.class)
                        .doesNotHaveBean(SenderClient.class)
                        .doesNotHaveBean(AntragSender.class));
    }

    @Test
    void failsFastWhenReceiverEnabledWithoutADestinationId() {
        Path signingKey = TestJwkKeys.writeSigningKey(tempDir);
        Path decryptionKey = TestJwkKeys.writeDecryptionKey(tempDir);

        contextRunner.withPropertyValues(
                        "fitconnect.sender.enabled=false",
                        "fitconnect.receiver.client-id=id",
                        "fitconnect.receiver.client-secret=secret",
                        "fitconnect.receiver.signing-key=file:" + signingKey,
                        "fitconnect.receiver.decryption-keys=file:" + decryptionKey,
                        "fitconnect.receiver.polling.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().isInstanceOf(FitConnectConfigurationException.class)
                        .hasMessageContaining("fitconnect.receiver.destination-ids"));
    }

    @Test
    void failsFastOnAMissingRequiredProperty() {
        contextRunner.withPropertyValues("fitconnect.receiver.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().isInstanceOf(FitConnectConfigurationException.class)
                        .hasMessageContaining("fitconnect.sender.client-id"));
    }
}
