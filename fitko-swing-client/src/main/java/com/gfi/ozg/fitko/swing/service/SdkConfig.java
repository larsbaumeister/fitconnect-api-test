package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.Connection;
import dev.fitko.fitconnect.rest.client.config.FitConnectEnvironment;
import dev.fitko.fitconnect.sdk.FitConnectSdk;

import java.util.Map;

/**
 * Builds the SDK's {@link FitConnectEnvironment} and {@link FitConnectSdk} for
 * one {@link Connection}. Every URL override that is set replaces the SDK's
 * built-in value, the rest stays as built in.
 */
public final class SdkConfig {

    private static final Map<String, FitConnectEnvironment> BUILT_IN = Map.of(
            "TEST", FitConnectEnvironment.TEST,
            "STAGE", FitConnectEnvironment.STAGE,
            "PROD", FitConnectEnvironment.PROD);

    private SdkConfig() {
    }

    public static FitConnectSdk createSdk(Connection connection) {
        requireCredentials(connection);
        return FitConnectSdk.fromConfigBuilder()
                .credentials(connection.resolvedClientId().trim(), connection.resolvedClientSecret().trim())
                .environment(environment(connection))
                .build();
    }

    public static void requireCredentials(Connection connection) {
        if (isBlank(connection.resolvedClientId()) || isBlank(connection.resolvedClientSecret())) {
            throw new IllegalStateException(connection.credentialsFromEnv
                    ? "Connection '" + connection.label + "': environment variables " + connection.clientIdEnvVar
                            + " / " + connection.clientSecretEnvVar + " are not set"
                    : "Connection '" + connection.label + "' has no client id / client secret");
        }
    }

    public static FitConnectEnvironment environment(Connection connection) {
        String name = connection.environment.trim().toUpperCase();
        FitConnectEnvironment builtIn = BUILT_IN.get(name);
        if (builtIn == null && (isBlank(connection.authBaseUrl) || isBlank(connection.submissionBaseUrl))) {
            throw new IllegalStateException("Connection '" + connection.label + "': environment '"
                    + connection.environment + "' is not built into the SDK (TEST, STAGE, PROD) - "
                    + "set the auth and submission base URLs");
        }
        // A custom environment starts from TEST's routing/portal URLs and flags.
        FitConnectEnvironment base = builtIn != null ? builtIn : FitConnectEnvironment.TEST;
        return FitConnectEnvironment.builder()
                .name(name)
                .authUrl(isBlank(connection.authBaseUrl) ? base.authUrl() : tokenUrl(connection.authBaseUrl.trim()))
                .routingUrl(base.routingUrl())
                .submissionApiUrl(isBlank(connection.submissionBaseUrl) ? base.submissionApiUrl()
                        : stripTrailingSlash(connection.submissionBaseUrl.trim()))
                .portalUrl(base.portalUrl())
                .destinationApiUrl(base.destinationApiUrl())
                .allowInsecureKeys(base.allowInsecureKeys())
                .build();
    }

    /** The SDK wants the full token endpoint; older saved connections hold only the auth base URL. */
    static String tokenUrl(String authUrl) {
        String url = stripTrailingSlash(authUrl);
        return url.endsWith("/token") ? url : url + "/token";
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
