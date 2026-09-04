package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.config.ApplicationConfigFactory;
import com.gfi.ozg.fitko.spring.receive.PollCycleGate;
import com.gfi.ozg.fitko.spring.receive.SafeguardedSubmissionRunner;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListenerFactory;
import com.gfi.ozg.fitko.spring.receive.SubmissionPollingService;
import com.gfi.ozg.fitko.spring.receive.SubmissionProcessor;
import com.gfi.ozg.fitko.spring.receive.cooldown.CacheRetryCooldownStore;
import com.gfi.ozg.fitko.spring.receive.cooldown.RetryCooldownStore;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.destination.ReceivingDestinations;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientFactory;
import com.gfi.ozg.fitko.spring.receive.destination.SubscriberClientPool;
import com.gfi.ozg.fitko.spring.receive.metrics.CompositeReceivePipelineMetrics;
import com.gfi.ozg.fitko.spring.receive.metrics.ReceivePipelineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Receiving ("Verwaltungssystem") side: one {@link SubscriberClientPool} per
 * configured destination (see {@link ApplicationConfigFactory}'s javadoc for
 * why clients aren't shared between destinations, and {@link
 * SubscriberClientPool}'s for why each destination gets a pool rather than a
 * single client) wrapped as a {@link ReceivingDestination}, a {@link
 * SubmissionProcessor} that turns a submission id into an {@code
 * SubmissionReceivedEvent}, a {@link SafeguardedSubmissionRunner} that
 * processes each poll page {@code fitconnect.receiver.polling.concurrency}
 * submissions at a time (carrying the submission-timeout and retry-cooldown
 * safeguards), and the {@link SubmissionPollingService} that drives it by
 * polling. {@link FitConnectCallbackAutoConfiguration} reuses the same {@link
 * ReceivingDestination} list and {@link SubmissionProcessor} for the optional
 * callback webhook endpoint.
 *
 * <p>Conditional on an {@link ApplicationConfig} bean existing (not directly
 * on {@code fitconnect.enabled}) so this backs off automatically whenever
 * {@link FitConnectAutoConfiguration} did - see {@link FitConnectSenderAutoConfiguration}.
 */
@AutoConfiguration(after = FitConnectAutoConfiguration.class)
@ConditionalOnBean(ApplicationConfig.class)
@ConditionalOnProperty(prefix = "fitconnect.receiver", name = "enabled", matchIfMissing = true)
@Slf4j
public class FitConnectReceiverAutoConfiguration {

    // Registered as infrastructure so @SubmissionEventListener(serviceIds = ...)
    // methods anywhere in the application get filtered per-service; without
    // it Spring's own DefaultEventListenerFactory would still run them, just
    // without the filtering (@EventListener is a meta-annotation on it).
    @Bean
    @ConditionalOnMissingBean
    public SubmissionEventListenerFactory submissionEventListenerFactory() {
        return new SubmissionEventListenerFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public SubscriberClientFactory subscriberClientFactory() {
        return ClientFactory::createSubscriberClient;
    }

    // Published as ReceivingDestinations, a dedicated holder type, rather
    // than a raw List<ReceivingDestination> - see that record's javadoc for
    // why a raw List<T> bean is a Spring autowiring footgun.
    @Bean
    @ConditionalOnMissingBean
    public ReceivingDestinations fitConnectReceivingDestinations(ApplicationConfig applicationConfig,
                                                                   SubscriberClientFactory subscriberClientFactory,
                                                                   FitConnectProperties properties) {
        List<FitConnectProperties.Receiver.Destination> configuredDestinations =
                properties.getReceiver().getDestinations();
        if (configuredDestinations.isEmpty()) {
            throw new FitConnectConfigurationException(
                    "fitconnect.receiver.destinations must be set when fitconnect.receiver.enabled=true");
        }

        int concurrency = resolvePollingConcurrency(properties);
        log.debug("Configuring {} receiving destination(s), up to {} submission(s) in parallel per destination",
                configuredDestinations.size(), concurrency);
        List<ReceivingDestination> destinations = new ArrayList<>(configuredDestinations.size());
        for (FitConnectProperties.Receiver.Destination destination : configuredDestinations) {
            if (destination.getId() == null) {
                throw new FitConnectConfigurationException(
                        "Every fitconnect.receiver.destinations entry needs an id");
            }
            log.debug("Building SubscriberClient pool for destination {} (callback {})",
                    destination.getId(), destination.getCallbackSecret() != null ? "enabled" : "disabled");
            SubscriberConfig subscriberConfig =
                    ApplicationConfigFactory.createSubscriberConfig(properties.getReceiver(), destination);
            ApplicationConfig destinationConfig =
                    ApplicationConfigFactory.withSubscriberConfig(applicationConfig, subscriberConfig);
            SubscriberClientPool clientPool = new SubscriberClientPool(
                    () -> createClient(subscriberClientFactory, destinationConfig), concurrency);
            destinations.add(new ReceivingDestination(
                    destination.getId(), clientPool, destination.getCallbackSecret()));
        }
        return new ReceivingDestinations(destinations);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubmissionProcessor submissionProcessor(ApplicationEventPublisher eventPublisher, FitConnectProperties properties,
                                                    ObjectProvider<ReceivePipelineMetrics> metrics) {
        return new SubmissionProcessor(eventPublisher, properties.getReceiver(), resolveMetrics(metrics));
    }

    // Retry-cooldown state on the Spring Cache abstraction. Only created when
    // fitconnect.receiver.polling.retry-cooldown is set (otherwise the poller
    // gets RetryCooldownStore.NONE and the feature costs nothing). Uses the
    // application's own CacheManager (e.g. Redis, shared across replicas) if
    // there is one; falls back to a self-pruning in-process cache otherwise,
    // so dev setups need no extra infrastructure. No @EnableCaching here - we
    // only consume a CacheManager, never enable caching proxies.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "fitconnect.receiver.polling", name = "retry-cooldown")
    public RetryCooldownStore fitConnectRetryCooldownStore(FitConnectProperties properties,
                                                           ObjectProvider<CacheManager> cacheManager) {
        FitConnectProperties.Polling polling = properties.getReceiver().getPolling();
        Duration cooldown = polling.getRetryCooldown();
        String cacheName = polling.getRetryCooldownCacheName();
        CacheManager manager = cacheManager.getIfAvailable();
        Cache cache = manager != null ? manager.getCache(cacheName) : null;
        if (cache != null) {
            return new CacheRetryCooldownStore(cache, cooldown);
        }
        if (manager != null) {
            log.info("No cache named '{}' in the configured CacheManager; using an in-process "
                    + "fallback for retry-cooldown state (not shared across replicas)", cacheName);
        }
        return CacheRetryCooldownStore.withInProcessFallback(cacheName, cooldown);
    }

    // Owns all of the receive-side concurrency: processes each destination's
    // poll page up to fitconnect.receiver.polling.concurrency submissions at a
    // time, while keeping every per-submission safeguard (submission-timeout,
    // retry-cooldown, metrics). AutoCloseable, so the container shuts its two
    // executors down on context close.
    @Bean
    @ConditionalOnMissingBean
    public SafeguardedSubmissionRunner fitConnectSafeguardedSubmissionRunner(SubmissionProcessor submissionProcessor,
                                                       ObjectProvider<ReceivePipelineMetrics> metrics,
                                                       ObjectProvider<RetryCooldownStore> retryCooldownStore,
                                                       FitConnectProperties properties) {
        return new SafeguardedSubmissionRunner(submissionProcessor, resolveMetrics(metrics),
                retryCooldownStore.getIfAvailable(() -> RetryCooldownStore.NONE),
                properties.getReceiver().getPolling());
    }

    // No initMethod/destroyMethod here: SubmissionPollingService implements
    // SmartLifecycle itself, so the container already calls start()/stop()
    // at the right point (start() honours isAutoStartup(), i.e.
    // fitconnect.receiver.polling.enabled) - wiring both would start it twice.
    @Bean
    @ConditionalOnMissingBean
    public SubmissionPollingService submissionPollingService(ReceivingDestinations fitConnectReceivingDestinations,
                                                       SafeguardedSubmissionRunner fitConnectSafeguardedSubmissionRunner,
                                                       FitConnectProperties properties,
                                                       ObjectProvider<ReceivePipelineMetrics> metrics,
                                                       ObjectProvider<PollCycleGate> pollCycleGate) {
        return new SubmissionPollingService(fitConnectReceivingDestinations, fitConnectSafeguardedSubmissionRunner,
                properties.getReceiver(), resolveMetrics(metrics),
                pollCycleGate.getIfAvailable(() -> PollCycleGate.DIRECT));
    }

    // The pipeline can have more than one ReceivePipelineMetrics: the
    // per-instance Micrometer meters (FitConnectReceiveMetricsAutoConfiguration)
    // and any extra ReceivePipelineMetrics bean a consumer contributes. Fan
    // out to all of them; NOOP beans (e.g. Micrometer absent) are dropped.
    private static ReceivePipelineMetrics resolveMetrics(ObjectProvider<ReceivePipelineMetrics> metrics) {
        List<ReceivePipelineMetrics> active = metrics.orderedStream()
                .filter(m -> m != ReceivePipelineMetrics.NOOP)
                .toList();
        return switch (active.size()) {
            case 0 -> ReceivePipelineMetrics.NOOP;
            case 1 -> active.get(0);
            default -> new CompositeReceivePipelineMetrics(active);
        };
    }

    private static SubscriberClient createClient(SubscriberClientFactory factory, ApplicationConfig config) {
        try {
            return factory.create(config);
        } catch (FitConnectInitialisationException e) {
            throw new FitConnectConfigurationException("Could not initialise the FIT-Connect SubscriberClient", e);
        }
    }

    private static int resolvePollingConcurrency(FitConnectProperties properties) {
        int concurrency = properties.getReceiver().getPolling().getConcurrency();
        if (concurrency < 1) {
            throw new FitConnectConfigurationException(
                    "fitconnect.receiver.polling.concurrency must be >= 1, was " + concurrency);
        }
        return concurrency;
    }
}
