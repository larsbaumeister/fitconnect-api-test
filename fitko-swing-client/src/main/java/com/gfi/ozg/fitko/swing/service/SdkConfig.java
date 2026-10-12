package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.Connection;
import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.Environment;
import dev.fitko.fitconnect.api.config.EnvironmentName;
import dev.fitko.fitconnect.api.config.SenderConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the SDK's {@link ApplicationConfig} for one {@link Connection}. URL
 * overrides are applied the same way {@code fitko-spring}'s
 * {@code ApplicationConfigFactory} does it: {@code override.merge(sdkDefault)}
 * keeps every set override and falls back to the SDK's built-in environment
 * for the rest.
 */
public final class SdkConfig {

    private SdkConfig() {
    }

    public static ApplicationConfig create(Connection connection) {
        String clientId = connection.resolvedClientId();
        String clientSecret = connection.resolvedClientSecret();
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalStateException(connection.credentialsFromEnv
                    ? "Connection '" + connection.label + "': environment variables " + connection.clientIdEnvVar
                            + " / " + connection.clientSecretEnvVar + " are not set"
                    : "Connection '" + connection.label + "' has no client id / client secret");
        }
        EnvironmentName environmentName = new EnvironmentName(connection.environment.trim());
        ApplicationConfig config = ApplicationConfig.builder()
                .activeEnvironment(environmentName)
                .senderConfig(new SenderConfig(clientId.trim(), clientSecret.trim()))
                .build();

        boolean hasOverride = !isBlank(connection.authBaseUrl) || !isBlank(connection.submissionBaseUrl);
        if (hasOverride || !config.getEnvironments().containsKey(environmentName)) {
            Environment override = new Environment();
            override.setAuthBaseUrl(blankToNull(connection.authBaseUrl));
            override.setSubmissionBaseUrls(isBlank(connection.submissionBaseUrl) ? null : List.of(connection.submissionBaseUrl.trim()));
            Map<EnvironmentName, Environment> environments = new HashMap<>(config.getEnvironments());
            Environment sdkDefault = environments.get(environmentName);
            if (sdkDefault == null) {
                // A custom environment: start from TEST's flags, but every URL must come from the connection.
                if (isBlank(connection.authBaseUrl) || isBlank(connection.submissionBaseUrl)) {
                    throw new IllegalStateException("Connection '" + connection.label + "': environment '"
                            + connection.environment + "' is not built into the SDK (TEST, STAGE, PROD) - "
                            + "set the auth and submission base URLs");
                }
                Environment test = environments.get(new EnvironmentName("TEST"));
                sdkDefault = new Environment(null, test.getRoutingBaseUrl(), null, test.getSelfServicePortalBaseUrl(),
                        test.getDestinationBaseUrl(), test.getEnableAutoReject(), test.getAllowInsecurePublicKey(),
                        test.getSkipSubmissionDataValidation());
            }
            environments.put(environmentName, override.merge(sdkDefault));
            config = config.withEnvironments(environments);
        }
        return config;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
