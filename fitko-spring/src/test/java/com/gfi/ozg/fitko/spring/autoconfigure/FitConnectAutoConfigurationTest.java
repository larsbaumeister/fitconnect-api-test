package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.client.SenderClient;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.receive.PollCycleGate;
import com.gfi.ozg.fitko.spring.receive.ShedLockPollCycleGate;
import com.gfi.ozg.fitko.spring.receive.SubmissionPollingService;
import com.gfi.ozg.fitko.spring.receive.cooldown.CacheRetryCooldownStore;
import com.gfi.ozg.fitko.spring.receive.cooldown.RetryCooldownStore;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
import com.gfi.ozg.fitko.spring.receive.health.FitConnectReceiverHealthIndicator;
import com.gfi.ozg.fitko.spring.receive.metrics.MicrometerReceivePipelineMetrics;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import com.gfi.ozg.fitko.spring.support.InMemoryLockProvider;
import com.gfi.ozg.fitko.spring.support.TestJwkKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

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
                    FitConnectReceiveMetricsAutoConfiguration.class,
                    FitConnectPollLockAutoConfiguration.class,
                    FitConnectReceiverAutoConfiguration.class,
                    FitConnectReceiveHealthAutoConfiguration.class));

    private String[] receiverOnlyProperties() {
        Path signingKey = TestJwkKeys.writeSigningKey(tempDir, "signing.json");
        Path decryptionKey = TestJwkKeys.writeDecryptionKey(tempDir, "decryption.json");
        return new String[] {
                "fitconnect.sender.enabled=false",
                "fitconnect.receiver.client-id=id",
                "fitconnect.receiver.client-secret=secret",
                "fitconnect.receiver.destinations[0].id=9f6bb611-df46-494a-9a98-a253f1362dc7",
                "fitconnect.receiver.destinations[0].signing-key=file:" + signingKey,
                "fitconnect.receiver.destinations[0].decryption-keys[0]=file:" + decryptionKey,
                "fitconnect.receiver.polling.enabled=false"
        };
    }

    @Test
    void backsOffEntirelyWhenDisabled() {
        contextRunner.withPropertyValues("fitconnect.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SubmissionSender.class)
                        .doesNotHaveBean(SubmissionPollingService.class));
    }

    @Test
    void wiresOnlyTheSenderSideWhenReceiverIsDisabled() {
        contextRunner.withPropertyValues(
                        "fitconnect.receiver.enabled=false",
                        "fitconnect.sender.client-id=id",
                        "fitconnect.sender.client-secret=secret")
                .run(context -> assertThat(context)
                        .hasSingleBean(SenderClient.class)
                        .hasSingleBean(SubmissionSender.class)
                        .doesNotHaveBean(SubscriberClientFactory.class)
                        .doesNotHaveBean(SubmissionPollingService.class));
    }

    @Test
    void wiresOnlyTheReceiverSideWhenSenderIsDisabled() {
        Path signingKeyA = TestJwkKeys.writeSigningKey(tempDir, "a-signing.json");
        Path decryptionKeyA = TestJwkKeys.writeDecryptionKey(tempDir, "a-decryption.json");
        Path signingKeyB = TestJwkKeys.writeSigningKey(tempDir, "b-signing.json");
        Path decryptionKeyB = TestJwkKeys.writeDecryptionKey(tempDir, "b-decryption.json");

        contextRunner.withPropertyValues(
                        "fitconnect.sender.enabled=false",
                        "fitconnect.receiver.client-id=id",
                        "fitconnect.receiver.client-secret=secret",
                        "fitconnect.receiver.destinations[0].id=9f6bb611-df46-494a-9a98-a253f1362dc7",
                        "fitconnect.receiver.destinations[0].signing-key=file:" + signingKeyA,
                        "fitconnect.receiver.destinations[0].decryption-keys[0]=file:" + decryptionKeyA,
                        "fitconnect.receiver.destinations[1].id=2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234",
                        "fitconnect.receiver.destinations[1].signing-key=file:" + signingKeyB,
                        "fitconnect.receiver.destinations[1].decryption-keys[0]=file:" + decryptionKeyB,
                        "fitconnect.receiver.polling.enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(SubscriberClientFactory.class)
                        .hasSingleBean(SubmissionPollingService.class)
                        .doesNotHaveBean(SenderClient.class)
                        .doesNotHaveBean(SubmissionSender.class));
    }

    @Test
    void wiresTheReceivePipelineObservabilityBeansOnTheReceiverSide() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .run(context -> assertThat(context)
                        .hasSingleBean(ReceivePipelineMetrics.class)
                        .hasSingleBean(FitConnectReceiverHealthIndicator.class));
    }

    @Test
    void receivePipelineMetricsIsMicrometerBackedWhenAMeterRegistryIsPresent() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(receiverOnlyProperties())
                .run(context -> assertThat(context.getBean(ReceivePipelineMetrics.class))
                        .isInstanceOf(MicrometerReceivePipelineMetrics.class));
    }

    @Test
    void fallsBackToNoOpMetricsWhenNoMeterRegistryIsAvailable() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .run(context -> assertThat(context.getBean(ReceivePipelineMetrics.class))
                        .isSameAs(ReceivePipelineMetrics.NOOP));
    }

    @Test
    void contributesNoObservabilityBeansWhenTheReceiverIsDisabled() {
        contextRunner.withPropertyValues(
                        "fitconnect.receiver.enabled=false",
                        "fitconnect.sender.client-id=id",
                        "fitconnect.sender.client-secret=secret")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ReceivePipelineMetrics.class)
                        .doesNotHaveBean(FitConnectReceiverHealthIndicator.class));
    }

    @Test
    void failsFastWhenReceiverEnabledWithoutADestination() {
        contextRunner.withPropertyValues(
                        "fitconnect.sender.enabled=false",
                        "fitconnect.receiver.client-id=id",
                        "fitconnect.receiver.client-secret=secret",
                        "fitconnect.receiver.polling.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().isInstanceOf(FitConnectConfigurationException.class)
                        .hasMessageContaining("fitconnect.receiver.destinations"));
    }

    @Test
    void failsFastOnAMissingRequiredProperty() {
        contextRunner.withPropertyValues("fitconnect.receiver.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().isInstanceOf(FitConnectConfigurationException.class)
                        .hasMessageContaining("fitconnect.sender.client-id"));
    }

    @Test
    void contributesNoRetryCooldownStoreWhenTheCooldownIsUnset() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .run(context -> assertThat(context).doesNotHaveBean(RetryCooldownStore.class));
    }

    @Test
    void usesAnInProcessRetryCooldownStoreWhenTheCooldownIsSetWithoutACacheManager() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .withPropertyValues("fitconnect.receiver.polling.retry-cooldown=20m")
                .run(context -> assertThat(context.getBean(RetryCooldownStore.class))
                        .isInstanceOf(CacheRetryCooldownStore.class));
    }

    @Test
    void backsTheRetryCooldownStoreWithTheApplicationsCacheManagerWhenOnePublishesTheNamedCache() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .withPropertyValues("fitconnect.receiver.polling.retry-cooldown=20m")
                .withBean(CacheManager.class,
                        () -> new ConcurrentMapCacheManager(RetryCooldownStore.CACHE_NAME))
                .run(context -> assertThat(context).hasSingleBean(RetryCooldownStore.class)
                        .getBean(RetryCooldownStore.class).isInstanceOf(CacheRetryCooldownStore.class));
    }

    @Test
    void contributesNoPollCycleGateWhenNoLockProviderBeanExists() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .run(context -> assertThat(context).doesNotHaveBean(PollCycleGate.class)
                        .hasSingleBean(SubmissionPollingService.class));
    }

    @Test
    void wiresAShedLockPollCycleGateWhenALockProviderBeanIsPresent() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .withBean(LockProvider.class, InMemoryLockProvider::new)
                .run(context -> assertThat(context.getBean(PollCycleGate.class))
                        .isInstanceOf(ShedLockPollCycleGate.class));
    }

    @Test
    void doesNotGatePollingWhenDistributedLockIsExplicitlyDisabled() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .withPropertyValues("fitconnect.receiver.polling.distributed-lock.enabled=false")
                .withBean(LockProvider.class, InMemoryLockProvider::new)
                .run(context -> assertThat(context).doesNotHaveBean(PollCycleGate.class));
    }

    @Test
    void pollLockAutoConfigurationBacksOffWhenShedLockIsNotOnTheClasspath() {
        contextRunner.withPropertyValues(receiverOnlyProperties())
                .withClassLoader(new FilteredClassLoader(LockProvider.class))
                .run(context -> assertThat(context).doesNotHaveBean(PollCycleGate.class)
                        .hasSingleBean(SubmissionPollingService.class));
    }
}
