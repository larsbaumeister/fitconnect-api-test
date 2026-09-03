package com.gfi.ozg.fitko.spring.autoconfigure;

import com.gfi.ozg.fitko.spring.receive.SubmissionPollingService;
import com.gfi.ozg.fitko.spring.receive.health.FitConnectReceiverHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Contributes {@link FitConnectReceiverHealthIndicator} (the {@code
 * fitConnectReceiver} entry under {@code /actuator/health}), reporting whether
 * every configured destination has been polled successfully recently.
 *
 * <p>{@code @ConditionalOnClass(HealthIndicator.class)}: a consumer without
 * the Spring Boot health API never pulls the optional {@code spring-boot-health}
 * jar and this simply contributes nothing. {@code @ConditionalOnBean(
 * SubmissionPollingService.class)} ties it to the receiver side actually being
 * wired, and {@code @ConditionalOnEnabledHealthIndicator} lets it be switched
 * off with {@code management.health.fitConnectReceiver.enabled=false} like any
 * other indicator.
 */
@AutoConfiguration(after = FitConnectReceiverAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(SubmissionPollingService.class)
public class FitConnectReceiveHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "fitConnectReceiverHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("fitConnectReceiver")
    public FitConnectReceiverHealthIndicator fitConnectReceiverHealthIndicator(SubmissionPollingService submissionPollingService) {
        return new FitConnectReceiverHealthIndicator(submissionPollingService);
    }
}
