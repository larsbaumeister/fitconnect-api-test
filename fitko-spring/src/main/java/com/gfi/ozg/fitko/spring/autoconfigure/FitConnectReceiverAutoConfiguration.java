package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.config.ApplicationConfigFactory;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListenerFactory;
import com.gfi.ozg.fitko.spring.receive.SubmissionPollingService;
import com.gfi.ozg.fitko.spring.receive.ReceivePipelineMetrics;
import com.gfi.ozg.fitko.spring.receive.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.ReceivingDestinations;
import com.gfi.ozg.fitko.spring.receive.SubmissionProcessor;
import com.gfi.ozg.fitko.spring.receive.SubscriberClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Receiving ("Verwaltungssystem") side: one {@link SubscriberClient} per
 * configured destination (see {@link ApplicationConfigFactory}'s javadoc for
 * why one isn't shared) wrapped as a {@link ReceivingDestination}, a {@link
 * SubmissionProcessor} that turns a submission id into an {@code
 * SubmissionReceivedEvent}, and the {@link SubmissionPollingService} that drives it
 * by polling. {@link FitConnectCallbackAutoConfiguration} reuses the same
 * {@link ReceivingDestination} list and {@link SubmissionProcessor} for the
 * optional callback webhook endpoint.
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

        log.debug("Configuring {} receiving destination(s)", configuredDestinations.size());
        List<ReceivingDestination> destinations = new ArrayList<>(configuredDestinations.size());
        for (FitConnectProperties.Receiver.Destination destination : configuredDestinations) {
            if (destination.getId() == null) {
                throw new FitConnectConfigurationException(
                        "Every fitconnect.receiver.destinations entry needs an id");
            }
            log.debug("Building SubscriberClient for destination {} (callback {})",
                    destination.getId(), destination.getCallbackSecret() != null ? "enabled" : "disabled");
            SubscriberConfig subscriberConfig =
                    ApplicationConfigFactory.createSubscriberConfig(properties.getReceiver(), destination);
            ApplicationConfig destinationConfig =
                    ApplicationConfigFactory.withSubscriberConfig(applicationConfig, subscriberConfig);
            SubscriberClient client = createClient(subscriberClientFactory, destinationConfig);
            destinations.add(new ReceivingDestination(destination.getId(), client, destination.getCallbackSecret()));
        }
        return new ReceivingDestinations(destinations);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubmissionProcessor submissionProcessor(ApplicationEventPublisher eventPublisher, FitConnectProperties properties,
                                                    ObjectProvider<ReceivePipelineMetrics> metrics) {
        return new SubmissionProcessor(eventPublisher, properties.getReceiver(), resolveMetrics(metrics));
    }

    // No initMethod/destroyMethod here: SubmissionPollingService implements
    // SmartLifecycle itself, so the container already calls start()/stop()
    // at the right point (start() honours isAutoStartup(), i.e.
    // fitconnect.receiver.polling.enabled) - wiring both would start it twice.
    @Bean
    @ConditionalOnMissingBean
    public SubmissionPollingService submissionPollingService(ReceivingDestinations fitConnectReceivingDestinations,
                                                       SubmissionProcessor submissionProcessor,
                                                       FitConnectProperties properties,
                                                       ObjectProvider<ReceivePipelineMetrics> metrics) {
        return new SubmissionPollingService(fitConnectReceivingDestinations, submissionProcessor, properties.getReceiver(),
                resolveMetrics(metrics));
    }

    // Micrometer is optional: FitConnectReceiveMetricsAutoConfiguration only
    // publishes a ReceivePipelineMetrics bean when it's on the classpath.
    private static ReceivePipelineMetrics resolveMetrics(ObjectProvider<ReceivePipelineMetrics> metrics) {
        return metrics.getIfAvailable(() -> ReceivePipelineMetrics.NOOP);
    }

    private static SubscriberClient createClient(SubscriberClientFactory factory, ApplicationConfig config) {
        try {
            return factory.create(config);
        } catch (FitConnectInitialisationException e) {
            throw new FitConnectConfigurationException("Could not initialise the FIT-Connect SubscriberClient", e);
        }
    }
}
