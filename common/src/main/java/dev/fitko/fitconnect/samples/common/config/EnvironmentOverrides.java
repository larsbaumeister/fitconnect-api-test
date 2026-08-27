package dev.fitko.fitconnect.samples.common.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional overrides for one FIT-Connect {@code environments.<name>} entry
 * (see the "Optionale Konfigurationsparameter" section of the Java-SDK docs).
 * Only fields that were explicitly set by the caller are written to the
 * generated YAML; everything else falls back to the SDK's built-in defaults
 * for the chosen environment (TEST/STAGE/PROD).
 */
public final class EnvironmentOverrides {

    private String authBaseUrl;
    private String routingBaseUrl;
    private final List<String> submissionBaseUrls = new ArrayList<>();
    private String selfServicePortalBaseUrl;
    private String destinationBaseUrl;
    private Boolean allowInsecurePublicKey;
    private Boolean skipSubmissionDataValidation;
    private Boolean enableAutoReject;

    public void setAuthBaseUrl(String authBaseUrl) {
        this.authBaseUrl = authBaseUrl;
    }

    public void setRoutingBaseUrl(String routingBaseUrl) {
        this.routingBaseUrl = routingBaseUrl;
    }

    public void addSubmissionBaseUrl(String submissionBaseUrl) {
        this.submissionBaseUrls.add(submissionBaseUrl);
    }

    public void setSelfServicePortalBaseUrl(String selfServicePortalBaseUrl) {
        this.selfServicePortalBaseUrl = selfServicePortalBaseUrl;
    }

    public void setDestinationBaseUrl(String destinationBaseUrl) {
        this.destinationBaseUrl = destinationBaseUrl;
    }

    public void setAllowInsecurePublicKey(Boolean allowInsecurePublicKey) {
        this.allowInsecurePublicKey = allowInsecurePublicKey;
    }

    public void setSkipSubmissionDataValidation(Boolean skipSubmissionDataValidation) {
        this.skipSubmissionDataValidation = skipSubmissionDataValidation;
    }

    public void setEnableAutoReject(Boolean enableAutoReject) {
        this.enableAutoReject = enableAutoReject;
    }

    public boolean isEmpty() {
        return authBaseUrl == null
                && routingBaseUrl == null
                && submissionBaseUrls.isEmpty()
                && selfServicePortalBaseUrl == null
                && destinationBaseUrl == null
                && allowInsecurePublicKey == null
                && skipSubmissionDataValidation == null
                && enableAutoReject == null;
    }

    /**
     * Appends an {@code environments: <environmentName>: {...}} block, or
     * nothing at all if no override was ever set.
     */
    public void writeTo(YamlWriter yaml, String environmentName) {
        if (isEmpty()) {
            return;
        }
        yaml.line(0, "environments:");
        yaml.line(1, environmentName + ":");
        if (authBaseUrl != null) {
            yaml.keyValue(2, "authBaseUrl", authBaseUrl);
        }
        if (routingBaseUrl != null) {
            yaml.keyValue(2, "routingBaseUrl", routingBaseUrl);
        }
        if (!submissionBaseUrls.isEmpty()) {
            yaml.quotedList(2, "submissionBaseUrls", submissionBaseUrls);
        }
        if (selfServicePortalBaseUrl != null) {
            yaml.keyValue(2, "selfServicePortalBaseUrl", selfServicePortalBaseUrl);
        }
        if (destinationBaseUrl != null) {
            yaml.keyValue(2, "destinationBaseUrl", destinationBaseUrl);
        }
        if (allowInsecurePublicKey != null) {
            yaml.keyValueRaw(2, "allowInsecurePublicKey", allowInsecurePublicKey.toString());
        }
        if (skipSubmissionDataValidation != null) {
            yaml.keyValueRaw(2, "skipSubmissionDataValidation", skipSubmissionDataValidation.toString());
        }
        if (enableAutoReject != null) {
            yaml.keyValueRaw(2, "enableAutoReject", enableAutoReject.toString());
        }
    }
}
