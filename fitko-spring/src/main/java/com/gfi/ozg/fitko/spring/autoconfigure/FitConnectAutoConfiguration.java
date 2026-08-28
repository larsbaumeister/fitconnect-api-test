package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.config.ApplicationConfigFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration: turns {@link FitConnectProperties} into the SDK's
 * {@link ApplicationConfig}, shared by both {@link FitConnectSenderAutoConfiguration}
 * and {@link FitConnectReceiverAutoConfiguration}.
 */
@AutoConfiguration
@EnableConfigurationProperties(FitConnectProperties.class)
@ConditionalOnProperty(prefix = "fitconnect", name = "enabled", matchIfMissing = true)
public class FitConnectAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationConfig fitConnectApplicationConfig(FitConnectProperties properties) {
        return ApplicationConfigFactory.create(properties);
    }
}
