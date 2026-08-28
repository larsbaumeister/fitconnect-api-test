package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.config.ApplicationConfigFactory;
import com.gfi.ozg.fitko.spring.receive.AntragEventListenerFactory;
import com.gfi.ozg.fitko.spring.receive.AntragPollingService;
import com.gfi.ozg.fitko.spring.receive.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.SubmissionProcessor;
import com.gfi.ozg.fitko.spring.receive.SubscriberClientFactory;
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
 * AntragReceivedEvent}, and the {@link AntragPollingService} that drives it
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
public class FitConnectReceiverAutoConfiguration {

    // Registered as infrastructure so @AntragEventListener(serviceIds = ...)
    // methods anywhere in the application get filtered per-service; without
    // it Spring's own DefaultEventListenerFactory would still run them, just
    // without the filtering (@EventListener is a meta-annotation on it).
    @Bean
    @ConditionalOnMissingBean
    public AntragEventListenerFactory antragEventListenerFactory() {
        return new AntragEventListenerFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public SubscriberClientFactory subscriberClientFactory() {
        return ClientFactory::createSubscriberClient;
    }

    @Bean
    @ConditionalOnMissingBean
    public List<ReceivingDestination> fitConnectReceivingDestinations(ApplicationConfig applicationConfig,
                                                                        SubscriberClientFactory subscriberClientFactory,
                                                                        FitConnectProperties properties) {
        List<FitConnectProperties.Receiver.Destination> configuredDestinations =
                properties.getReceiver().getDestinations();
        if (configuredDestinations.isEmpty()) {
            throw new FitConnectConfigurationException(
                    "fitconnect.receiver.destinations must be set when fitconnect.receiver.enabled=true");
        }

        List<ReceivingDestination> destinations = new ArrayList<>(configuredDestinations.size());
        for (FitConnectProperties.Receiver.Destination destination : configuredDestinations) {
            if (destination.getId() == null) {
                throw new FitConnectConfigurationException(
                        "Every fitconnect.receiver.destinations entry needs an id");
            }
            SubscriberConfig subscriberConfig =
                    ApplicationConfigFactory.createSubscriberConfig(properties.getReceiver(), destination);
            ApplicationConfig destinationConfig =
                    ApplicationConfigFactory.withSubscriberConfig(applicationConfig, subscriberConfig);
            SubscriberClient client = createClient(subscriberClientFactory, destinationConfig);
            destinations.add(new ReceivingDestination(destination.getId(), client, destination.getCallbackSecret()));
        }
        return destinations;
    }

    @Bean
    @ConditionalOnMissingBean
    public SubmissionProcessor submissionProcessor(ApplicationEventPublisher eventPublisher, FitConnectProperties properties) {
        return new SubmissionProcessor(eventPublisher, properties.getReceiver());
    }

    // No initMethod/destroyMethod here: AntragPollingService implements
    // SmartLifecycle itself, so the container already calls start()/stop()
    // at the right point (start() honours isAutoStartup(), i.e.
    // fitconnect.receiver.polling.enabled) - wiring both would start it twice.
    @Bean
    @ConditionalOnMissingBean
    public AntragPollingService antragPollingService(List<ReceivingDestination> fitConnectReceivingDestinations,
                                                       SubmissionProcessor submissionProcessor,
                                                       FitConnectProperties properties) {
        return new AntragPollingService(fitConnectReceivingDestinations, submissionProcessor, properties.getReceiver());
    }

    private static SubscriberClient createClient(SubscriberClientFactory factory, ApplicationConfig config) {
        try {
            return factory.create(config);
        } catch (FitConnectInitialisationException e) {
            throw new FitConnectConfigurationException("Could not initialise the FIT-Connect SubscriberClient", e);
        }
    }
}
