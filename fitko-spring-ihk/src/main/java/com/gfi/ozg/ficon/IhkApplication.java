package com.gfi.ozg.ficon;

import com.gfi.ozg.ficon.processstarter.ProcessStarterRouter;
import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.EnvironmentName;
import dev.fitko.fitconnect.api.config.SenderConfig;
import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({FitConnectProperties.class, ProcessStarterRouter.Properties.class})
public class IhkApplication {

    public static void main(String[] args) {
        SpringApplication.run(IhkApplication.class, args);
    }

    /**
     * The SDK's own client for sending submissions - inject it and send a
     * {@code SendableSubmission} built with {@code SendableSubmission.Builder()}.
     * Only created when {@code fitconnect.sender.client-id} is set.
     */
    @Bean
    @ConditionalOnProperty("fitconnect.sender.client-id")
    SenderClient senderClient(FitConnectProperties properties) {
        FitConnectProperties.Sender sender = properties.sender();
        return ClientFactory.createSenderClient(ApplicationConfig.builder()
                .activeEnvironment(new EnvironmentName(properties.environment()))
                .senderConfig(new SenderConfig(sender.clientId(), sender.clientSecret()))
                .build());
    }
}
