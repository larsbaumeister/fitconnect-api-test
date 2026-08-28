package com.gfi.ozg.fitko.spring;

import com.gfi.ozg.fitko.spring.receive.DefaultOutcome;
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
 */
@ConfigurationProperties(prefix = "fitconnect")
public class FitConnectProperties {

    /** Master switch; set to {@code false} to disable this starter entirely. */
    private boolean enabled = true;

    /** FIT-Connect environment: {@code TEST}, {@code STAGE}, {@code PROD}, or a custom environment name. */
    private String environment = "TEST";

    private final Http http = new Http();
    private final BaseUrls baseUrls = new BaseUrls();
    private final Sender sender = new Sender();
    private final Receiver receiver = new Receiver();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Http getHttp() {
        return http;
    }

    public BaseUrls getBaseUrls() {
        return baseUrls;
    }

    public Sender getSender() {
        return sender;
    }

    public Receiver getReceiver() {
        return receiver;
    }

    /** HTTP client timeouts; unset values keep the SDK's own default (30s). */
    public static class Http {

        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration writeTimeout;

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getWriteTimeout() {
            return writeTimeout;
        }

        public void setWriteTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
        }
    }

    /**
     * Endpoint overrides for {@link #environment}; leave unset to use the
     * SDK's built-in defaults for that environment. Mainly useful for
     * pointing the SDK at a local stub server in tests.
     */
    public static class BaseUrls {

        private String auth;
        private String routing;
        private List<String> submission = new ArrayList<>();
        private String selfServicePortal;
        private String destination;

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        public String getRouting() {
            return routing;
        }

        public void setRouting(String routing) {
            this.routing = routing;
        }

        public List<String> getSubmission() {
            return submission;
        }

        public void setSubmission(List<String> submission) {
            this.submission = submission;
        }

        public String getSelfServicePortal() {
            return selfServicePortal;
        }

        public void setSelfServicePortal(String selfServicePortal) {
            this.selfServicePortal = selfServicePortal;
        }

        public String getDestination() {
            return destination;
        }

        public void setDestination(String destination) {
            this.destination = destination;
        }
    }

    /** Sending ("Onlinedienst") side: enables the {@code AntragSender} bean. */
    public static class Sender {

        /** Set to {@code false} if this application only receives, never sends. */
        private boolean enabled = true;

        /** Sender client id issued by the Self-Service-Portal. */
        private String clientId;

        /** Sender client secret issued by the Self-Service-Portal. */
        private String clientSecret;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }

    /** Receiving ("Verwaltungssystem") side: enables the {@code AntragPollingService}. */
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public List<Destination> getDestinations() {
            return destinations;
        }

        public void setDestinations(List<Destination> destinations) {
            this.destinations = destinations;
        }

        public boolean isAllowInsecurePublicKey() {
            return allowInsecurePublicKey;
        }

        public void setAllowInsecurePublicKey(boolean allowInsecurePublicKey) {
            this.allowInsecurePublicKey = allowInsecurePublicKey;
        }

        public boolean isSkipSubmissionDataValidation() {
            return skipSubmissionDataValidation;
        }

        public void setSkipSubmissionDataValidation(boolean skipSubmissionDataValidation) {
            this.skipSubmissionDataValidation = skipSubmissionDataValidation;
        }

        public boolean isDisableAutoReject() {
            return disableAutoReject;
        }

        public void setDisableAutoReject(boolean disableAutoReject) {
            this.disableAutoReject = disableAutoReject;
        }

        public DefaultOutcome getDefaultOutcome() {
            return defaultOutcome;
        }

        public void setDefaultOutcome(DefaultOutcome defaultOutcome) {
            this.defaultOutcome = defaultOutcome;
        }

        public Polling getPolling() {
            return polling;
        }

        public Callback getCallback() {
            return callback;
        }

        /**
         * One Zustellpunkt (destination) this application receives on, and
         * the credentials/keys it was registered with. {@link #getClientId()}/
         * {@link #getClientSecret()} are optional and fall back to {@link
         * Receiver#getClientId()}/{@link Receiver#getClientSecret()} - set
         * them here only if this destination was registered under a
         * different Self-Service-Portal client than the others.
         */
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

            public UUID getId() {
                return id;
            }

            public void setId(UUID id) {
                this.id = id;
            }

            public String getClientId() {
                return clientId;
            }

            public void setClientId(String clientId) {
                this.clientId = clientId;
            }

            public String getClientSecret() {
                return clientSecret;
            }

            public void setClientSecret(String clientSecret) {
                this.clientSecret = clientSecret;
            }

            public Resource getSigningKey() {
                return signingKey;
            }

            public void setSigningKey(Resource signingKey) {
                this.signingKey = signingKey;
            }

            public List<Resource> getDecryptionKeys() {
                return decryptionKeys;
            }

            public void setDecryptionKeys(List<Resource> decryptionKeys) {
                this.decryptionKeys = decryptionKeys;
            }

            public String getCallbackSecret() {
                return callbackSecret;
            }

            public void setCallbackSecret(String callbackSecret) {
                this.callbackSecret = callbackSecret;
            }
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

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }
        }
    }

    /** How often {@code AntragPollingService} checks the destination for new submissions. */
    public static class Polling {

        /** Set to {@code false} to disable automatic polling (e.g. to only pick up submissions on demand). */
        private boolean enabled = true;

        /** Delay before the first poll after startup. */
        private Duration initialDelay = Duration.ofSeconds(5);

        /** Delay between the end of one poll and the start of the next. */
        private Duration interval = Duration.ofSeconds(30);

        /** Paging limit when listing available submissions. */
        private int limit = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}
