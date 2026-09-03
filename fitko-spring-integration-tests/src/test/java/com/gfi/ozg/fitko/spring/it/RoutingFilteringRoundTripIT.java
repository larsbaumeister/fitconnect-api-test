package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionEventListener;
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
import org.springframework.core.annotation.Order;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-5 - {@code @SubmissionEventListener} routing against a live round trip:
 * a listener filtered to one LeiKa service only fires for that service, an
 * unfiltered {@code @EventListener} fires for everything, several listeners
 * coexist, and {@code @Order} is respected (an audit listener observes before
 * the resolving one).
 *
 * <p>This class hardcodes its two service ids (they must be compile-time
 * constants for the annotation) and ignores {@code FITCONNECT_IT_SERVICE_ID}.
 * The fixture destination must accept arbitrary service types - the default
 * on TEST.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RoutingFilteringRoundTripIT extends AbstractRoundTripIT {

    static final String SERVICE_A = "urn:de:fim:leika:leistung:99050035001000"; // "Gewerbeanmeldung"
    static final String SERVICE_B = "urn:de:fim:leika:leistung:99050035002000"; // "Bauantrag"

    @Autowired
    FilteringListeners listeners;

    @BeforeEach
    void resetListeners() {
        listeners.reset();
    }

    @Test
    void aSubmissionOnlyReachesTheListenerFilteredToItsService() {
        String markerA = Payloads.newMarker(getClass());
        listeners.acceptMarker(markerA);
        SentSubmission sentA = send(submission(SERVICE_A, markerA));
        awaitHit(markerA, "resolve");

        assertThat(listeners.hits(markerA)).contains("serviceA", "audit").doesNotContain("serviceB");

        String markerB = Payloads.newMarker(getClass());
        listeners.acceptMarker(markerB);
        SentSubmission sentB = send(submission(SERVICE_B, markerB));
        awaitHit(markerB, "resolve");

        assertThat(listeners.hits(markerB)).contains("serviceB", "audit").doesNotContain("serviceA");

        assertNoRedelivery(markerA);
        assertNoRedelivery(markerB);
        log.info("routed submissions {} / {}", sentA.getSubmissionId(), sentB.getSubmissionId());
    }

    @Test
    void theAuditListenerObservesBeforeTheResolvingListener() {
        String marker = Payloads.newMarker(getClass());
        listeners.acceptMarker(marker);

        send(submission(SERVICE_A, marker));
        awaitHit(marker, "resolve");

        List<String> hits = listeners.hits(marker);
        assertThat(hits.indexOf("audit"))
                .as("@Order(0) audit listener runs before the @Order(10) resolving listener; hits=%s", hits)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(hits.indexOf("resolve"));
    }

    private void awaitHit(String marker, String listenerName) {
        Awaitility.await("listener " + listenerName + " sees " + marker)
                .atMost(RECEIVE_TIMEOUT)
                .pollInterval(RECEIVE_POLL)
                .until(() -> listeners.hits(marker).contains(listenerName));
    }

    private void assertNoRedelivery(String marker) {
        long seen = listeners.auditCount(marker);
        sleep(REDELIVERY_WINDOW);
        assertThat(listeners.auditCount(marker))
                .as("submission %s must not be redelivered after being accepted", marker)
                .isEqualTo(seen);
    }

    private static SubmissionToSend submission(String serviceId, String marker) {
        return SubmissionToSend.builder(serviceId, "fitko-spring IT (routing)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId())
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        FilteringListeners filteringListeners() {
            return new FilteringListeners();
        }
    }

    /** One listener per filtering style, all watching the same event stream, recording hits per marker. */
    static class FilteringListeners {

        private final Map<String, List<String>> hitsByMarker = new ConcurrentHashMap<>();
        private volatile String acceptMarker;

        @SubmissionEventListener(serviceIds = SERVICE_A)
        void onServiceA(SubmissionReceivedEvent event) {
            record(event, "serviceA");
        }

        @SubmissionEventListener(serviceIds = SERVICE_B)
        void onServiceB(SubmissionReceivedEvent event) {
            record(event, "serviceB");
        }

        @EventListener
        @Order(0)
        void audit(SubmissionReceivedEvent event) {
            record(event, "audit");
        }

        @EventListener
        @Order(10)
        void resolve(SubmissionReceivedEvent event) {
            record(event, "resolve");
            IncomingSubmission submission = event.getSubmission();
            marker(event).filter(m -> m.equals(acceptMarker)).ifPresent(m -> {
                if (!submission.isResolved()) {
                    submission.accept();
                }
            });
        }

        void acceptMarker(String marker) {
            this.acceptMarker = marker;
        }

        List<String> hits(String marker) {
            return List.copyOf(hitsByMarker.getOrDefault(marker, List.of()));
        }

        long auditCount(String marker) {
            return hits(marker).stream().filter("audit"::equals).count();
        }

        void reset() {
            hitsByMarker.clear();
            acceptMarker = null;
        }

        private void record(SubmissionReceivedEvent event, String listenerName) {
            marker(event).ifPresent(m ->
                    hitsByMarker.computeIfAbsent(m, k -> new CopyOnWriteArrayList<>()).add(listenerName));
        }

        private static Optional<String> marker(SubmissionReceivedEvent event) {
            return Payloads.findMarker(event.getSubmission().getDataAsString());
        }
    }
}
