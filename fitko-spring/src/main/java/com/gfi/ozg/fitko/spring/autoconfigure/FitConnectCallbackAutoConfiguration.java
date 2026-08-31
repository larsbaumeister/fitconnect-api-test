package com.gfi.ozg.fitko.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfi.ozg.fitko.spring.receive.ReceivingDestinations;
import com.gfi.ozg.fitko.spring.receive.SubmissionProcessor;
import com.gfi.ozg.fitko.spring.receive.callback.FitConnectCallbackController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers {@link FitConnectCallbackController}, the optional webhook
 * endpoint FIT-Connect can push new-submission notifications to instead of
 * (or alongside) {@link com.gfi.ozg.fitko.spring.receive.AntragPollingService}
 * polling for them.
 *
 * <p>Opt-in on purpose ({@code havingValue = "true"}, no {@code
 * matchIfMissing}) - unlike polling, exposing an HTTP endpoint is an
 * infrastructure change (needs {@code spring-boot-starter-web}, needs the
 * URL registered with FIT-Connect and reachable from the internet), not
 * something to switch on implicitly. {@code @ConditionalOnClass} means this
 * simply stays off with no error if that optional dependency isn't present,
 * even if {@code fitconnect.receiver.callback.enabled=true} is set.
 */
@AutoConfiguration(after = FitConnectReceiverAutoConfiguration.class)
@ConditionalOnClass(RestController.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnBean(SubmissionProcessor.class)
@ConditionalOnProperty(prefix = "fitconnect.receiver.callback", name = "enabled", havingValue = "true")
public class FitConnectCallbackAutoConfiguration {

    // The SDK's callback DTOs (NewEventsCallback, SubmissionForPickup, ...)
    // use plain Jackson 2 annotations, but Spring Boot 4's own auto-configured
    // JSON support defaults to Jackson 3 (a different ObjectMapper type
    // entirely) - so this parses the callback body with its own Jackson 2
    // mapper rather than assuming the application has one of those wired up
    // for its own REST layer already. jackson-databind (2.x) itself is
    // always present regardless, as a transitive dependency of the SDK.
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper fitConnectCallbackObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public FitConnectCallbackController fitConnectCallbackController(ReceivingDestinations fitConnectReceivingDestinations,
                                                                       SubmissionProcessor submissionProcessor,
                                                                       ObjectMapper fitConnectCallbackObjectMapper) {
        return new FitConnectCallbackController(fitConnectReceivingDestinations, submissionProcessor, fitConnectCallbackObjectMapper);
    }
}
