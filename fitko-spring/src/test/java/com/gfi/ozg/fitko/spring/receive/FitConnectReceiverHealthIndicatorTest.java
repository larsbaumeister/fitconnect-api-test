package com.gfi.ozg.fitko.spring.receive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FitConnectReceiverHealthIndicatorTest {

    private static final UUID DESTINATION_A = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID DESTINATION_B = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    private final AntragPollingService pollingService = mock(AntragPollingService.class);
    private final FitConnectReceiverHealthIndicator indicator = new FitConnectReceiverHealthIndicator(pollingService);

    @BeforeEach
    void defaults() {
        lenient().when(pollingService.isAutoStartup()).thenReturn(true);
        lenient().when(pollingService.isRunning()).thenReturn(true);
        lenient().when(pollingService.initialDelay()).thenReturn(Duration.ofSeconds(5));
        lenient().when(pollingService.pollInterval()).thenReturn(Duration.ofSeconds(30));
        lenient().when(pollingService.destinationIds()).thenReturn(List.of(DESTINATION_A, DESTINATION_B));
        // started well outside the startup grace window
        lenient().when(pollingService.startedAt()).thenReturn(Instant.now().minus(Duration.ofHours(1)));
    }

    @Test
    void unknownWhenPollingIsDisabled() {
        when(pollingService.isAutoStartup()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("polling", "disabled");
    }

    @Test
    void upWhenEveryDestinationWasPolledRecently() {
        Instant justNow = Instant.now().minusSeconds(2);
        when(pollingService.lastSuccessfulPollByDestination())
                .thenReturn(Map.of(DESTINATION_A, justNow, DESTINATION_B, justNow));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry(DESTINATION_A.toString(), justNow.toString())
                .containsEntry(DESTINATION_B.toString(), justNow.toString());
    }

    @Test
    void downWhenADestinationHasNotBeenPolledSuccessfullyWithinTheStalenessWindow() {
        Instant fresh = Instant.now().minusSeconds(2);
        Instant stale = Instant.now().minus(Duration.ofMinutes(10)); // > 5s + 3*30s
        when(pollingService.lastSuccessfulPollByDestination())
                .thenReturn(Map.of(DESTINATION_A, fresh, DESTINATION_B, stale));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(DESTINATION_B.toString(), stale.toString());
    }

    @Test
    void downWhenADestinationHasNeverBeenPolledAndStartupGraceHasElapsed() {
        when(pollingService.lastSuccessfulPollByDestination())
                .thenReturn(Map.of(DESTINATION_A, Instant.now()));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(DESTINATION_B.toString(), "never");
    }

    @Test
    void upDuringTheStartupGracePeriodEvenBeforeAnyDestinationHasBeenPolled() {
        when(pollingService.startedAt()).thenReturn(Instant.now().minusSeconds(1));
        when(pollingService.lastSuccessfulPollByDestination()).thenReturn(Map.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(DESTINATION_A.toString(), "no successful poll yet");
    }
}
