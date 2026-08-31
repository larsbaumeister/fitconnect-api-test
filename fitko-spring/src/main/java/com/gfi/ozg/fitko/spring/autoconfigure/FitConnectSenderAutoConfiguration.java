package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectInitialisationException;
import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import com.gfi.ozg.fitko.spring.send.DefaultSubmissionSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Sending ("Onlinedienst") side: a {@link SenderClient} and the {@link SubmissionSender} bean built on top of it.
 *
 * <p>Conditional on an {@link ApplicationConfig} bean existing (not directly
 * on {@code fitconnect.enabled}) so this backs off automatically whenever
 * {@link FitConnectAutoConfiguration} did - the one place that flag is
 * interpreted - and stays in sync with it even if that condition ever grows
 * more cases than a single property check.
 */
@AutoConfiguration(after = FitConnectAutoConfiguration.class)
@ConditionalOnBean(ApplicationConfig.class)
@ConditionalOnProperty(prefix = "fitconnect.sender", name = "enabled", matchIfMissing = true)
public class FitConnectSenderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SenderClient fitConnectSenderClient(ApplicationConfig applicationConfig) {
        try {
            return ClientFactory.createSenderClient(applicationConfig);
        } catch (FitConnectInitialisationException e) {
            throw new FitConnectConfigurationException("Could not initialise the FIT-Connect SenderClient", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public SubmissionSender submissionSender(SenderClient fitConnectSenderClient) {
        return new DefaultSubmissionSender(fitConnectSenderClient);
    }
}
