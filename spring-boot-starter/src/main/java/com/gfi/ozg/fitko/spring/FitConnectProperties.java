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
 *     signing-key: file:/etc/fitconnect/signing_key.json
 *     decryption-keys: file:/etc/fitconnect/decryption_key.json
 *     destination-ids:
 *       - 9f6bb611-df46-494a-9a98-a253f1362dc7
 *       - 2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234
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

        /** Subscriber client id issued by the Self-Service-Portal. */
        private String clientId;

        /** Subscriber client secret issued by the Self-Service-Portal. */
        private String clientSecret;

        /** Private signing key JWK, e.g. {@code file:/etc/fitconnect/signing_key.json}. */
        private Resource signingKey;

        /** Private decryption key JWKs; more than one supports key rollover. */
        private List<Resource> decryptionKeys = new ArrayList<>();

        /**
         * Zustellpunkt (destination) ids polled for incoming submissions; at
         * least one is required whenever {@code fitconnect.receiver.enabled}
         * is {@code true}. One {@link com.gfi.ozg.fitko.spring.receive.AntragPollingService}
         * handles the whole list, polling each destination once per cycle -
         * use this to receive several Leistungen (each registered against
         * its own destination) in one application.
         */
        private List<UUID> destinationIds = new ArrayList<>();

        /** Accept self-signed destination certificates. Never enable this in PROD. */
        private boolean allowInsecurePublicKey = false;

        /** Skip local schema validation of received submission data. */
        private boolean skipSubmissionDataValidation = false;

        /** Do not auto-reject submissions that fail validation. */
        private boolean disableAutoReject = false;

        /** What to do with a submission no {@code @EventListener} explicitly accepted/rejected. */
        private DefaultOutcome defaultOutcome = DefaultOutcome.LEAVE;

        private final Polling polling = new Polling();

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

        public List<UUID> getDestinationIds() {
            return destinationIds;
        }

        public void setDestinationIds(List<UUID> destinationIds) {
            this.destinationIds = destinationIds;
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
