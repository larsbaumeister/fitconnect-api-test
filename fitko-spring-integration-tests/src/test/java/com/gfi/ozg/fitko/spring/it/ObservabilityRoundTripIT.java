package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.receive.SubmissionPollingService;
import com.gfi.ozg.fitko.spring.receive.health.FitConnectReceiverHealthIndicator;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-9 - the optional observability integrations, live: after a real round
 * trip the {@code fitconnect.receive.*} Micrometer meters have moved, and the
 * {@code fitConnectReceiver} health indicator tracks the poller thread
 * (UP while running, DOWN once stopped).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RecordingListenerConfig.class)
class ObservabilityRoundTripIT extends AbstractRoundTripIT {

    @Autowired
    RecordingListener listener;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    FitConnectReceiverHealthIndicator healthIndicator;

    @Autowired
    SubmissionPollingService pollingService;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @AfterEach
    void ensurePollerRunning() {
        if (pollingService.isAutoStartup() && !pollingService.isRunning()) {
            pollingService.start();
        }
    }

    @Test
    void receivePipelineMetersMoveOnARoundTrip() {
        String destination = ITCredentials.destinationId().toString();
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (metrics)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId())
                .build());
        awaitReceived(listener, sent.getSubmissionId());

        assertThat(meterRegistry.get("fitconnect.receive.poll")
                .tag("destination", destination).tag("outcome", "success").timer().count())
                .as("successful poll cycles recorded")
                .isPositive();
        assertThat(meterRegistry.get("fitconnect.receive.submissions.found")
                .tag("destination", destination).counter().count())
                .as("submissions listed as available")
                .isPositive();
        assertThat(meterRegistry.get("fitconnect.receive.submissions.processed")
                .tag("destination", destination).counter().count())
                .as("submissions downloaded and published")
                .isPositive();
    }

    @Test
    void healthIndicatorTracksThePollerThread() {
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);

        pollingService.stop();
        Awaitility.await("poller stopped")
                .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> !pollingService.isRunning());
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

        pollingService.start();
        Awaitility.await("poller restarted")
                .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(pollingService::isRunning);
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
