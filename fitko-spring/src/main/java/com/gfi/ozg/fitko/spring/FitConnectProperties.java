package com.gfi.ozg.fitko.spring;

import com.gfi.ozg.fitko.spring.receive.DefaultOutcome;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuration for the FIT-Connect Spring Boot starter, bound from any
 * regular Spring property source under the {@code fitconnect} prefix (e.g.
 * {@code application.yml}, environment variables, a config server, ...).
 *
 * <p>Minimal example:
 * <pre>{@code
 * fitconnect:
 *   environment: TEST
 *   sender:
 *     client-id: ...
 *     client-secret: ...
 *   receiver:
 *     client-id: ...
 *     client-secret: ...
 *     destinations:
 *       - id: 9f6bb611-df46-494a-9a98-a253f1362dc7
 *         signing-key: file:/etc/fitconnect/destination-a/signing_key.json
 *         decryption-keys: file:/etc/fitconnect/destination-a/decryption_key.json
 *       - id: 2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234
 *         signing-key: file:/etc/fitconnect/destination-b/signing_key.json
 *         decryption-keys: file:/etc/fitconnect/destination-b/decryption_key.json
 * }</pre>
 *
 * <p>This class and every nested class here is a plain {@code @Getter}/
 * {@code @Setter} Lombok bean - {@code spring-boot-configuration-processor}
 * reads Lombok's generated accessors and each field's javadoc the same way
 * it would hand-written ones, so {@code fitconnect.*} still gets full IDE
 * autocomplete/descriptions. A {@code final} field (like {@link #http} or
 * {@link #receiver} below - always-present nested config, never replaced
 * outright) simply gets no setter generated; Lombok skips those on its own.
 */
@ConfigurationProperties(prefix = "fitconnect")
@Getter
@Setter
public class FitConnectProperties {

    /** Master switch; set to {@code false} to disable this starter entirely. */
    private boolean enabled = true;

    /** FIT-Connect environment: {@code TEST}, {@code STAGE}, {@code PROD}, or a custom environment name. */
    private String environment = "TEST";

    private final Http http = new Http();
    private final BaseUrls baseUrls = new BaseUrls();
    private final Sender sender = new Sender();
    private final Receiver receiver = new Receiver();

    /** HTTP client timeouts; unset values keep the SDK's own default (30s). */
    @Getter
    @Setter
    public static class Http {

        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration writeTimeout;
    }

    /**
     * Endpoint overrides for {@link #environment}; leave unset to use the
     * SDK's built-in defaults for that environment. Mainly useful for
     * pointing the SDK at a local stub server in tests.
     */
    @Getter
    @Setter
    public static class BaseUrls {

        private String auth;
        private String routing;
        private List<String> submission = new ArrayList<>();
        private String selfServicePortal;
        private String destination;
    }

    /** Sending ("Onlinedienst") side: enables the {@code SubmissionSender} bean. */
    @Getter
    @Setter
    public static class Sender {

        /** Set to {@code false} if this application only receives, never sends. */
        private boolean enabled = true;

        /** Sender client id issued by the Self-Service-Portal. */
        private String clientId;

        /** Sender client secret issued by the Self-Service-Portal. */
        private String clientSecret;
    }

    /** Receiving ("Verwaltungssystem") side: enables the {@code SubmissionPollingService}. */
    @Getter
    @Setter
    public static class Receiver {

        /** Set to {@code false} if this application only sends, never receives. */
        private boolean enabled = true;

        /**
         * Default subscriber client id issued by the Self-Service-Portal,
         * used by any {@link Destination} that doesn't set its own via
         * {@link Destination#getClientId()}.
         */
        private String clientId;

        /** Default subscriber client secret; see {@link #getClientId()}. */
        private String clientSecret;

        /**
         * Every Zustellpunkt (destination) this application receives on. At
         * least one is required whenever {@code fitconnect.receiver.enabled}
         * is {@code true}. One {@link com.gfi.ozg.fitko.spring.receive.SubmissionPollingService}
         * handles the whole list, polling each destination once per cycle -
         * use this to receive several Leistungen (each with its own
         * destination) in one application.
         *
         * <p>Each destination has its own signing/decryption keys, since a
         * FIT-Connect Zustellpunkt is registered with its own key pair
         * regardless of which subscriber client polls it (e.g. distinct
         * authorities each managing their own certificates, even where they
         * happen to share one client-id/client-secret). Internally this
         * means one SDK {@code SubscriberClient} per destination.
         */
        private List<Destination> destinations = new ArrayList<>();

        /** Accept self-signed destination certificates. Never enable this in PROD. */
        private boolean allowInsecurePublicKey = false;

        /** Skip local schema validation of received submission data. */
        private boolean skipSubmissionDataValidation = false;

        /** Do not auto-reject submissions that fail validation. */
        private boolean disableAutoReject = false;

        /** What to do with a submission no {@code @EventListener} explicitly accepted/rejected. */
        private DefaultOutcome defaultOutcome = DefaultOutcome.LEAVE;

        private final Polling polling = new Polling();
        private final Callback callback = new Callback();
        private final SharedMetrics sharedMetrics = new SharedMetrics();

        /**
         * Opt-in fleet-wide receive metrics. The per-instance {@code
         * fitconnect.receive.*} Micrometer meters are always local to one
         * replica; with several replicas you normally aggregate them in your
         * monitoring backend. Enable this to <em>also</em> keep the counts in
         * Redis, shared by every replica, and re-publish them as {@code
         * fitconnect.receive.fleet.*} gauges - so a scrape of any single
         * replica shows the whole fleet's totals (aggregate those with {@code
         * max}, never {@code sum}).
         *
         * <p>Requires Spring Data Redis on the classpath (an optional
         * dependency of this starter - bring {@code
         * spring-boot-starter-data-redis} and configure {@code
         * spring.data.redis.*}) and Micrometer for the gauges. Inert with
         * neither.
         */
        @Getter
        @Setter
        public static class SharedMetrics {

            /** {@code true} to keep receive counts in Redis and expose the {@code fitconnect.receive.fleet.*} gauges. */
            private boolean enabled = false;

            /**
             * Prefix for the Redis keys the shared counts live under. Change
             * it only if several unrelated applications share one Redis and
             * would otherwise collide.
             */
            private String keyPrefix = "fitconnect:receive:";
        }

        /**
         * One Zustellpunkt (destination) this application receives on, and
         * the credentials/keys it was registered with. {@link #getClientId()}/
         * {@link #getClientSecret()} are optional and fall back to {@link
         * Receiver#getClientId()}/{@link Receiver#getClientSecret()} - set
         * them here only if this destination was registered under a
         * different Self-Service-Portal client than the others.
         */
        @Getter
        @Setter
        public static class Destination {

            /** Zustellpunkt (destination) id to poll/receive callbacks for. */
            private UUID id;

            /** Overrides {@link Receiver#getClientId()} for this destination. */
            private String clientId;

            /** Overrides {@link Receiver#getClientSecret()} for this destination. */
            private String clientSecret;

            /** This destination's private signing key JWK, e.g. {@code file:/etc/fitconnect/signing_key.json}. */
            private Resource signingKey;

            /** This destination's private decryption key JWKs; more than one supports key rollover. */
            private List<Resource> decryptionKeys = new ArrayList<>();

            /**
             * The secret configured for this destination's callback (the
             * same value given to FIT-Connect when registering its {@code
             * Callback} via {@code DestinationClient}). Required for this
             * destination's submissions to be accepted at {@link
             * Callback#getPath()}; a destination without one is simply never
             * reachable through the webhook endpoint, polling still works
             * regardless.
             */
            private String callbackSecret;
        }

        /**
         * The webhook endpoint FIT-Connect can push new-submission
         * notifications to, instead of (or alongside) this application
         * polling for them. Disabled by default; enabling it requires
         * {@code spring-boot-starter-web} on the classpath (it's an optional
         * dependency of this starter) and registering the endpoint's URL as
         * each destination's {@code Callback} with FIT-Connect separately
         * (via {@code DestinationClient} - outside this starter's scope, see
         * the README).
         */
        @Getter
        @Setter
        public static class Callback {

            /**
             * Set to {@code true} to expose the webhook endpoint. Every
             * destination that should actually receive callbacks also needs
             * its own {@link Destination#getCallbackSecret()} set - polling
             * keeps working for any destination that doesn't.
             */
            private boolean enabled = false;

            /**
             * Base path the webhook endpoint is mapped to; the destination
             * id is always appended as its last segment, e.g. {@code
             * /fitconnect/callback/<destinationId>} with the default value -
             * that full URL, reachable from the internet, is what gets
             * registered as the destination's {@code Callback} with
             * FIT-Connect.
             */
            private String path = "/fitconnect/callback";
        }
    }

    /** How often {@code SubmissionPollingService} checks the destination for new submissions. */
    @Getter
    @Setter
    public static class Polling {

        /** Set to {@code false} to disable automatic polling (e.g. to only pick up submissions on demand). */
        private boolean enabled = true;

        /** Delay before the first poll after startup. */
        private Duration initialDelay = Duration.ofSeconds(5);

        /** Delay between the end of one poll and the start of the next. */
        private Duration interval = Duration.ofSeconds(30);

        /** Paging limit when listing available submissions. */
        private int limit = 100;

        /**
         * Max time allowed to download, decrypt, publish and let listeners
         * handle one submission before it's abandoned for this cycle and
         * counted as a failure (see {@link #getRetryCooldown()}). Guards
         * against a hung network call or a blocking bug in a listener
         * stalling the single-threaded poller indefinitely - see "Known
         * limitations" in {@code java-samples/docs/architecture.md}.
         *
         * <p>Enforced via {@link Thread#interrupt()} on a best-effort basis:
         * the poller thread moves on to the next submission promptly, but
         * the abandoned worker thread only actually stops once whatever it
         * was blocked in honours the interrupt - a listener stuck in a tight
         * uninterruptible loop keeps its thread alive (leaked) until it
         * eventually returns on its own.
         */
        private Duration submissionTimeout = Duration.ofSeconds(10);

        /**
         * How many of one destination's available submissions are downloaded,
         * decrypted, published and resolved <em>in parallel</em> within a
         * single poll cycle. Destinations are still polled one after another;
         * this parallelises the submissions <em>within</em> each destination's
         * page.
         *
         * <p>Every safeguard still applies per submission: each gets its own
         * full {@link #getSubmissionTimeout()} budget and its own {@link
         * #getRetryCooldown()} bookkeeping, and the poll cycle still blocks
         * until the whole page is done (so cycles never overlap and a ShedLock
         * lock still spans the cycle). Because the FIT-Connect SDK's {@code
         * SubscriberClient} is not safe for concurrent use, one client is
         * created per unit of concurrency per destination (lazily, on first
         * contention) - so each increment adds, per destination, one OAuth
         * client-credentials login and one schema initialisation. Keep it
         * modest (e.g. 4-16), sized against real payload sizes and the
         * FIT-Connect API's rate limits. Must be {@code >= 1}; {@code 1}
         * restores strictly-sequential processing.
         */
        private int concurrency = 8;

        /**
         * How long a submission that failed processing (including a {@link
         * #getSubmissionTimeout()} timeout) is skipped on later poll cycles
         * before being retried again. {@code null} (the default) disables
         * this entirely - a failed submission is re-fetched and retried on
         * every single cycle, exactly as before this option existed.
         *
         * <p>Opt in with e.g. {@code 20m}. The submission stays on the
         * delivery service throughout - nothing is rejected or otherwise
         * resolved - it's simply not re-fetched until the cooldown elapses,
         * so one permanently-broken submission stops burning part of every
         * poll cycle. A submission that succeeds clears its cooldown state
         * immediately.
         */
        private Duration retryCooldown;

        /**
         * Name of the Spring {@link org.springframework.cache.Cache} that
         * holds {@link #getRetryCooldown()} state - one entry per
         * currently-failing submission id, value = the ISO-8601 timestamp of
         * its last failure. Only relevant when {@code retry-cooldown} is set.
         *
         * <p>If the application has a {@code CacheManager} (e.g. Redis,
         * shared across replicas), configure a cache of this name there -
         * ideally with a TTL at least as long as {@code retry-cooldown} - and
         * cooldowns are then shared fleet-wide. With no {@code CacheManager}
         * the starter uses a self-pruning in-process fallback and this name
         * is cosmetic.
         */
        private String retryCooldownCacheName = "fitconnect-retry-cooldown";

        private final DistributedLock distributedLock = new DistributedLock();

        /**
         * Opt-in mutual exclusion so that, when this application runs as
         * several replicas all polling the same FIT-Connect destination(s),
         * only one replica runs any given poll cycle instead of every replica
         * re-downloading and re-publishing every unresolved submission (~N x
         * the work for N replicas). Listeners must be idempotent regardless,
         * so this is a cost/efficiency measure, not a correctness fix.
         *
         * <p>Inactive unless ShedLock ({@code
         * net.javacrumbs.shedlock:shedlock-core}, an optional dependency of
         * this starter) is on the classpath <em>and</em> the application
         * declares a {@code net.javacrumbs.shedlock.core.LockProvider} bean
         * (the consumer picks the backend: JDBC, Redis, Mongo, ...). With
         * neither, every replica polls independently, exactly as before. The
         * callback webhook endpoint is never gated by this lock.
         */
        @Getter
        @Setter
        public static class DistributedLock {

            /** Set to {@code false} to keep ShedLock on the classpath but not gate polling with it. */
            private boolean enabled = true;

            /**
             * Safety-net upper bound on how long the fleet-wide poll lock
             * stays held if the replica holding it dies mid-cycle without
             * releasing it. Unset derives {@code 10 x} {@link
             * Polling#getInterval()}. Too low and a legitimately long cycle
             * lets a second replica start overlapping; too high and a hard
             * crash stalls polling fleet-wide until it expires.
             */
            private Duration lockAtMostFor;

            /**
             * Lower bound on how long the poll lock stays held even when the
             * cycle finishes sooner - this is what actually spaces polls out
             * across the fleet (without it an empty cycle releases
             * immediately and another replica polls the same queue straight
             * away). Unset derives {@link Polling#getInterval()}.
             */
            private Duration lockAtLeastFor;
        }
    }
}
