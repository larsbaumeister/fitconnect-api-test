package dev.fitko.fitconnect.samples.common.cli;

import dev.fitko.fitconnect.samples.common.config.EnvironmentOverrides;
import dev.fitko.fitconnect.samples.common.config.HttpTimeouts;
import dev.fitko.fitconnect.samples.common.config.LocalSchemaMapping;

import java.util.Set;

/**
 * Parses the CLI options that are identical between the sender and the
 * receiver sample: environment URL/behaviour overrides, HTTP timeouts, and
 * locally cached submission-data schemas. Kept here once so both apps stay in
 * sync instead of maintaining two copies of the same option names.
 */
public final class SharedOptionArgs {

    /** Boolean-flag option names contributed by {@link #parseEnvironmentOverrides}. */
    public static final Set<String> BOOLEAN_FLAGS = Set.of(
            "allow-insecure-public-key", "skip-submission-data-validation", "disable-auto-reject");

    private SharedOptionArgs() {
    }

    public static EnvironmentOverrides parseEnvironmentOverrides(ArgumentReader reader) {
        EnvironmentOverrides overrides = new EnvironmentOverrides();
        reader.get("auth-base-url").ifPresent(overrides::setAuthBaseUrl);
        reader.get("routing-base-url").ifPresent(overrides::setRoutingBaseUrl);
        reader.getAll("submission-base-url").forEach(overrides::addSubmissionBaseUrl);
        reader.get("self-service-portal-base-url").ifPresent(overrides::setSelfServicePortalBaseUrl);
        reader.get("destination-base-url").ifPresent(overrides::setDestinationBaseUrl);
        if (reader.isFlagSet("allow-insecure-public-key")) {
            overrides.setAllowInsecurePublicKey(true);
        }
        if (reader.isFlagSet("skip-submission-data-validation")) {
            overrides.setSkipSubmissionDataValidation(true);
        }
        if (reader.isFlagSet("disable-auto-reject")) {
            overrides.setEnableAutoReject(false);
        }
        return overrides;
    }

    public static HttpTimeouts parseHttpTimeouts(ArgumentReader reader) {
        HttpTimeouts timeouts = new HttpTimeouts();
        reader.getInt("connect-timeout").ifPresent(timeouts::setConnectionTimeoutSeconds);
        reader.getInt("read-timeout").ifPresent(timeouts::setReadTimeoutSeconds);
        reader.getInt("write-timeout").ifPresent(timeouts::setWriteTimeoutSeconds);
        return timeouts;
    }

    public static LocalSchemaMapping parseLocalSchemas(ArgumentReader reader) {
        LocalSchemaMapping mapping = new LocalSchemaMapping();
        for (String[] entry : reader.getKeyValuePairs("local-schema")) {
            mapping.add(entry[0], entry[1]);
        }
        return mapping;
    }
}
