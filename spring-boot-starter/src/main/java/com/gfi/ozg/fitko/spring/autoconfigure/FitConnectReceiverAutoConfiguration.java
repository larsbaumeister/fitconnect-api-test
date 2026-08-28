package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.AntragEventListenerFactory;
import com.gfi.ozg.fitko.spring.receive.AntragPollingService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Receiving ("Verwaltungssystem") side: a {@link SubscriberClient} and the
 * {@link AntragPollingService} that polls it, publishing an {@code
 * AntragReceivedEvent} for every submission.
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
    public SubscriberClient fitConnectSubscriberClient(ApplicationConfig applicationConfig) {
        try {
            return ClientFactory.createSubscriberClient(applicationConfig);
        } catch (FitConnectInitialisationException e) {
            throw new FitConnectConfigurationException("Could not initialise the FIT-Connect SubscriberClient", e);
        }
    }

    // No initMethod/destroyMethod here: AntragPollingService implements
    // SmartLifecycle itself, so the container already calls start()/stop()
    // at the right point (start() honours isAutoStartup(), i.e.
    // fitconnect.receiver.polling.enabled) - wiring both would start it twice.
    @Bean
    @ConditionalOnMissingBean
    public AntragPollingService antragPollingService(SubscriberClient fitConnectSubscriberClient,
                                                       ApplicationEventPublisher eventPublisher,
                                                       FitConnectProperties properties) {
        if (properties.getReceiver().getDestinationIds().isEmpty()) {
            throw new FitConnectConfigurationException(
                    "fitconnect.receiver.destination-ids must be set when fitconnect.receiver.enabled=true");
        }
        return new AntragPollingService(fitConnectSubscriberClient, eventPublisher,
                properties.getReceiver().getDestinationIds(), properties.getReceiver());
    }
}
