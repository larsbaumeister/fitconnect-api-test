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
 *         signing-key: file:/etc/fitconnect/ihk-a/signing_key.json
 *         decryption-keys: file:/etc/fitconnect/ihk-a/decryption_key.json
 *       - id: 2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234
 *         signing-key: file:/etc/fitconnect/ihk-b/signing_key.json
 *         decryption-keys: file:/etc/fitconnect/ihk-b/decryption_key.json
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

    /** Sending ("Onlinedienst") side: enables the {@code AntragSender} bean. */
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

    /** Receiving ("Verwaltungssystem") side: enables the {@code AntragPollingService}. */
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
         * is {@code true}. One {@link com.gfi.ozg.fitko.spring.receive.AntragPollingService}
         * handles the whole list, polling each destination once per cycle -
         * use this to receive several Leistungen (each with its own
         * destination) in one application.
         *
         * <p>Each destination has its own signing/decryption keys, since a
         * FIT-Connect Zustellpunkt is registered with its own key pair
         * regardless of which subscriber client polls it (e.g. distinct
         * Kammern each managing their own certificates, even where they
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

    /** How often {@code AntragPollingService} checks the destination for new submissions. */
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
         * limitations" in {@code docs/developer/architecture.md}.
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
    }
}
