package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-8 - the poller's safeguards against one bad submission, exercised live:
 * {@code submission-timeout} abandons a stuck submission for the cycle without
 * losing it, {@code retry-cooldown} stops a repeatedly-failing submission from
 * being retried every single cycle, and {@code polling.limit} pages a backlog
 * across cycles instead of dropping it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "fitconnect.receiver.polling.submission-timeout=4s",
        "fitconnect.receiver.polling.retry-cooldown=8s",
        "fitconnect.receiver.polling.limit=2"
})
class PollingSafeguardsRoundTripIT extends AbstractRoundTripIT {

    private static final Duration RESOLVE_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    SafeguardListener listener;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void aSubmissionThatOverrunsTheTimeoutIsAbandonedForTheCyclebutNotLost() {
        String marker = Payloads.newMarker(getClass());
        listener.slowMarker = marker;
        listener.slowSleepMillis = 12_000; // well past the 4s submission-timeout; only the first sighting sleeps

        SentSubmission sent = send(submission(marker));

        Awaitility.await("submission " + sent.getSubmissionId() + " eventually accepted after the timeout")
                .atMost(RESOLVE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> listener.accepted.contains(marker));

        assertThat(listener.attempts(marker))
                .as("first attempt abandoned by submission-timeout, a later one succeeded")
                .isGreaterThanOrEqualTo(2);
        assertNoRedelivery(marker);
    }

    @Test
    void aRepeatedlyFailingSubmissionIsThrottledByTheRetryCooldown() {
        String marker = Payloads.newMarker(getClass());
        listener.throwMarker = marker;

        SentSubmission sent = send(submission(marker));

        // wait for the first failure, then measure the retry rate over the next ~24s
        Awaitility.await("first processing attempt for " + sent.getSubmissionId())
                .atMost(RECEIVE_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> listener.attempts(marker) >= 1);
        int attemptsAtStart = listener.attempts(marker);
        sleep(Duration.ofSeconds(24));
        int retries = listener.attempts(marker) - attemptsAtStart;

        // interval is 3s, cooldown is 8s: ~3 retries in 24s, definitely not ~8
        assertThat(retries)
                .as("retry-cooldown throttles retries to roughly one per cooldown, not one per poll interval")
                .isBetween(1, 5);

        // stop failing -> it is picked up promptly and accepted
        listener.throwMarker = null;
        Awaitility.await("submission " + sent.getSubmissionId() + " accepted once it stops failing")
                .atMost(RESOLVE_TIMEOUT).pollInterval(Duration.ofSeconds(3))
                .until(() -> listener.accepted.contains(marker));
        assertNoRedelivery(marker);
    }

    @Test
    void aBacklogLargerThanTheLimitDrainsAcrossCycles() {
        String markerA = Payloads.newMarker(getClass());
        String markerB = Payloads.newMarker(getClass());
        String markerC = Payloads.newMarker(getClass());
        List<String> markers = List.of(markerA, markerB, markerC);

        markers.forEach(m -> send(submission(m)));

        Awaitility.await("all 3 submissions delivered and accepted despite limit=2")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> listener.accepted.containsAll(markers));

        // with limit=2 the third cannot be first-seen in the same cycle as the other two
        Duration spread = Duration.between(listener.earliestFirstSighting(markers), listener.latestFirstSighting(markers));
        assertThat(spread)
                .as("first sightings of 3 submissions with limit=2 span more than one poll cycle")
                .isGreaterThan(Duration.ofSeconds(2));
        markers.forEach(this::assertNoRedelivery);
    }

    private void assertNoRedelivery(String marker) {
        int attempts = listener.attempts(marker);
        sleep(REDELIVERY_WINDOW);
        assertThat(listener.attempts(marker))
                .as("accepted submission %s must not be redelivered", marker)
                .isEqualTo(attempts);
    }

    private static SubmissionToSend submission(String marker) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (safeguards)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId())
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        SafeguardListener safeguardListener() {
            return new SafeguardListener();
        }
    }

    static class SafeguardListener {

        volatile String slowMarker;
        volatile long slowSleepMillis = 12_000;
        volatile String throwMarker;

        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Map<String, Instant> firstSighting = new ConcurrentHashMap<>();
        final Set<String> accepted = ConcurrentHashMap.newKeySet();

        @EventListener
        void onSubmission(SubmissionReceivedEvent event) throws InterruptedException {
            String marker = Payloads.findMarker(event.getSubmission().getDataAsString()).orElse("<none>");
            attempts.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
            firstSighting.putIfAbsent(marker, Instant.now());

            if (marker.equals(throwMarker)) {
                throw new IllegalStateException("deliberate failure for " + marker);
            }
            if (marker.equals(slowMarker)) {
                slowMarker = null; // only the first sighting is slow
                Thread.sleep(slowSleepMillis);
            }
            event.getSubmission().accept();
            accepted.add(marker);
        }

        int attempts(String marker) {
            AtomicInteger c = attempts.get(marker);
            return c == null ? 0 : c.get();
        }

        Instant earliestFirstSighting(List<String> markers) {
            return markers.stream().map(firstSighting::get).filter(Objects::nonNull)
                    .min(Instant::compareTo).orElseThrow();
        }

        Instant latestFirstSighting(List<String> markers) {
            return markers.stream().map(firstSighting::get).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElseThrow();
        }

        void reset() {
            slowMarker = null;
            throwMarker = null;
            slowSleepMillis = 12_000;
            attempts.clear();
            firstSighting.clear();
            accepted.clear();
        }
    }
}
