package com.gfi.ozg.fitko.spring.receive;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FitConnectReceiverHealthIndicatorTest {

    private final SubmissionPollingService pollingService = mock(SubmissionPollingService.class);
    private final FitConnectReceiverHealthIndicator indicator = new FitConnectReceiverHealthIndicator(pollingService);

    @Test
    void upWhenThePollerIsRunning() {
        when(pollingService.isAutoStartup()).thenReturn(true);
        when(pollingService.isRunning()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("polling", "running");
    }

    @Test
    void downWhenPollingIsEnabledButThePollerIsNotRunning() {
        when(pollingService.isAutoStartup()).thenReturn(true);
        when(pollingService.isRunning()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("polling", "stopped");
    }

    @Test
    void upWhenPollingIsDisabled() {
        when(pollingService.isAutoStartup()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("polling", "disabled");
    }
}
