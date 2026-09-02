package com.gfi.ozg.fitko.spring.autoconfigure;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.gfi.ozg.fitko.spring.receive.PollCycleGate;
import com.gfi.ozg.fitko.spring.receive.ShedLockPollCycleGate;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;

/**
 * Opt-in coordination of {@link com.gfi.ozg.fitko.spring.receive.SubmissionPollingService}
 * across replicas: when the application runs several instances all polling the
 * same FIT-Connect destination(s), a ShedLock lock ensures only one instance
 * runs any given poll cycle instead of every instance re-downloading and
 * re-publishing every unresolved submission.
 *
 * <p>{@code @ConditionalOnClass(LockProvider.class)}: a consumer that doesn't
 * put {@code net.javacrumbs.shedlock:shedlock-core} on the classpath never
 * loads this class and the poller keeps using {@link PollCycleGate#DIRECT}
 * (every replica polls independently, exactly as before). The {@link
 * PollCycleGate} bean is additionally {@code @ConditionalOnBean(LockProvider.class)}
 * - the consumer must declare their own {@code LockProvider} (JDBC, Redis,
 * Mongo, ...) - and can be turned off with
 * {@code fitconnect.receiver.polling.distributed-lock.enabled=false}.
 *
 * <p>The lock name is derived from {@code fitconnect.receiver.destinations}
 * directly (not the {@code ReceivingDestinations} bean), so this config needs
 * nothing from {@link FitConnectReceiverAutoConfiguration} and is safely
 * ordered {@code before} it - the {@link PollCycleGate} bean is then present
 * when the poller is built.
 */
@AutoConfiguration(after = FitConnectAutoConfiguration.class, before = FitConnectReceiverAutoConfiguration.class)
@ConditionalOnClass(LockProvider.class)
@ConditionalOnBean(ApplicationConfig.class)
@ConditionalOnProperty(prefix = "fitconnect.receiver", name = "enabled", matchIfMissing = true)
public class FitConnectPollLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(LockProvider.class)
    @ConditionalOnProperty(prefix = "fitconnect.receiver.polling.distributed-lock", name = "enabled",
            matchIfMissing = true)
    public PollCycleGate fitConnectPollCycleGate(LockProvider lockProvider, FitConnectProperties properties) {
        FitConnectProperties.Polling polling = properties.getReceiver().getPolling();
        List<UUID> destinationIds = properties.getReceiver().getDestinations().stream()
                .map(FitConnectProperties.Receiver.Destination::getId)
                .toList();
        return new ShedLockPollCycleGate(lockProvider, destinationIds, polling.getDistributedLock(),
                polling.getInterval());
    }
}
