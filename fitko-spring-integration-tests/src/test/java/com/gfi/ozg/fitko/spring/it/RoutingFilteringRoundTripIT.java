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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-5 - {@code @SubmissionEventListener} routing against a live round trip:
 * a listener filtered to the submitted service fires, a listener filtered to
 * a <em>different</em> service does not, an unfiltered {@code @EventListener}
 * fires for everything, and {@code @Order} is respected (an audit listener
 * observes before the resolving one).
 *
 * <p>Only one service id is ever sent (a TEST Zustellpunkt is registered for
 * a fixed set of services). {@link #SERVICE_A} is hardcoded because the
 * annotation needs a constant; the class skips if
 * {@code FITCONNECT_IT_SERVICE_ID} points somewhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RoutingFilteringRoundTripIT extends AbstractRoundTripIT {

    /** The service actually sent - must match FITCONNECT_IT_SERVICE_ID (its default). */
    static final String SERVICE_A = "urn:de:fim:leika:leistung:99050035001000";

    /** A different LeiKa key, never sent - the negative-filter case. */
    static final String SERVICE_OTHER = "urn:de:fim:leika:leistung:99000000000000";

    @Autowired
    FilteringListeners listeners;

    @BeforeAll
    static void requireDefaultService() {
        Assumptions.assumeTrue(SERVICE_A.equals(ITCredentials.serviceId()),
                "RoutingFilteringRoundTripIT sends " + SERVICE_A + " but FITCONNECT_IT_SERVICE_ID is "
                        + ITCredentials.serviceId());
    }

    @BeforeEach
    void resetListeners() {
        listeners.reset();
    }

    @Test
    void aSubmissionOnlyReachesTheListenerFilteredToItsService() {
        String marker = Payloads.newMarker(getClass());
        listeners.acceptMarker(marker);

        SentSubmission sent = send(submission(marker));
        awaitHit(marker, "resolve");

        assertThat(listeners.hits(marker))
                .as("the filtered listener for the sent service, and the unfiltered one, fire")
                .contains("serviceA", "audit");
        assertThat(listeners.hits(marker))
                .as("a listener filtered to a different service does not fire")
                .doesNotContain("serviceOther");

        assertNoRedelivery(marker);
        log.info("routed submission {}", sent.getSubmissionId());
    }

    @Test
    void theAuditListenerObservesBeforeTheResolvingListener() {
        String marker = Payloads.newMarker(getClass());
        listeners.acceptMarker(marker);

        send(submission(marker));
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

    private static SubmissionToSend submission(String marker) {
        return SubmissionToSend.builder(SERVICE_A, "fitko-spring IT (routing)", DataFormat.XML,
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

        @SubmissionEventListener(serviceIds = SERVICE_OTHER)
        void onServiceOther(SubmissionReceivedEvent event) {
            record(event, "serviceOther");
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
