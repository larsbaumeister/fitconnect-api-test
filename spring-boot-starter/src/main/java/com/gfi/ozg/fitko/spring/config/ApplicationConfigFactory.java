package com.gfi.ozg.fitko.spring.config;

import com.gfi.ozg.fitko.spring.FitConnectConfigurationException;
import com.gfi.ozg.fitko.spring.FitConnectProperties;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.config.Environment;
import dev.fitko.fitconnect.api.config.EnvironmentName;
import dev.fitko.fitconnect.api.config.SenderConfig;
import dev.fitko.fitconnect.api.config.SubscriberConfig;
import dev.fitko.fitconnect.api.config.http.HttpConfig;
import dev.fitko.fitconnect.api.config.http.Timeouts;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link FitConnectProperties} into the SDK's {@link ApplicationConfig},
 * entirely through the SDK's own builders/setters - no intermediate YAML
 * document. The one piece of SDK behaviour worth calling out is {@link
 * Environment#merge}: {@code override.merge(sdkDefault)} keeps every
 * non-null/non-empty field of {@code override} and falls back to {@code
 * sdkDefault} for the rest - the receiver wins, the argument fills gaps
 * (verified against the SDK's own {@code ApplicationConfigLoader}, which
 * applies environment overrides the same way).
 */
public final class ApplicationConfigFactory {

    private ApplicationConfigFactory() {
    }

    public static ApplicationConfig create(FitConnectProperties properties) {
        EnvironmentName environmentName = new EnvironmentName(properties.getEnvironment());

        ApplicationConfig.ApplicationConfigBuilder builder = ApplicationConfig.builder()
                .activeEnvironment(environmentName);

        if (properties.getSender().isEnabled()) {
            builder.senderConfig(toSenderConfig(properties.getSender()));
        }
        if (properties.getReceiver().isEnabled()) {
            builder.subscriberConfig(toSubscriberConfig(properties.getReceiver()));
        }

        HttpConfig httpConfig = toHttpConfig(properties.getHttp());
        if (httpConfig != null) {
            builder.httpConfig(httpConfig);
        }

        ApplicationConfig config = builder.build();

        Environment override = toEnvironmentOverride(properties);
        if (override != null) {
            Map<EnvironmentName, Environment> environments = new HashMap<>(config.getEnvironments());
            Environment sdkDefault = environments.getOrDefault(environmentName, new Environment());
            environments.put(environmentName, override.merge(sdkDefault));
            config = config.withEnvironments(environments);
        }

        return config;
    }

    private static SenderConfig toSenderConfig(FitConnectProperties.Sender sender) {
        requireText(sender.getClientId(), "fitconnect.sender.client-id");
        requireText(sender.getClientSecret(), "fitconnect.sender.client-secret");
        return new SenderConfig(sender.getClientId(), sender.getClientSecret());
    }

    private static SubscriberConfig toSubscriberConfig(FitConnectProperties.Receiver receiver) {
        requireText(receiver.getClientId(), "fitconnect.receiver.client-id");
        requireText(receiver.getClientSecret(), "fitconnect.receiver.client-secret");
        if (receiver.getSigningKey() == null) {
            throw missingProperty("fitconnect.receiver.signing-key");
        }
        if (receiver.getDecryptionKeys().isEmpty()) {
            throw missingProperty("fitconnect.receiver.decryption-keys");
        }
        List<JWK> decryptionKeys = new ArrayList<>(receiver.getDecryptionKeys().size());
        for (Resource resource : receiver.getDecryptionKeys()) {
            decryptionKeys.add(readJwk(resource, "fitconnect.receiver.decryption-keys"));
        }
        return SubscriberConfig.builder()
                .clientId(receiver.getClientId())
                .clientSecret(receiver.getClientSecret())
                .privateSigningKey(readJwk(receiver.getSigningKey(), "fitconnect.receiver.signing-key"))
                .privateDecryptionKeys(decryptionKeys)
                .build();
    }

    /**
     * Unlike the SDK's own file-path-based config loading, this reads the
     * key material directly - a configured {@link Resource} can be anything
     * Spring can resolve (a mounted file, a classpath entry, ...), it never
     * has to point at a real file on disk.
     */
    private static JWK readJwk(Resource resource, String property) {
        try {
            String json = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
            return JWK.parse(json);
        } catch (IOException e) {
            throw new FitConnectConfigurationException("Could not read '" + property + "' from " + resource, e);
        } catch (ParseException e) {
            throw new FitConnectConfigurationException("'" + property + "' at " + resource + " is not a valid JWK", e);
        }
    }

    private static HttpConfig toHttpConfig(FitConnectProperties.Http http) {
        if (http.getConnectTimeout() == null && http.getReadTimeout() == null && http.getWriteTimeout() == null) {
            return null;
        }
        Timeouts.TimeoutsBuilder timeouts = Timeouts.builder();
        if (http.getConnectTimeout() != null) {
            timeouts.connectionTimeout((int) http.getConnectTimeout().toSeconds());
        }
        if (http.getReadTimeout() != null) {
            timeouts.readTimeout((int) http.getReadTimeout().toSeconds());
        }
        if (http.getWriteTimeout() != null) {
            timeouts.writeTimeout((int) http.getWriteTimeout().toSeconds());
        }
        return HttpConfig.builder().timeouts(timeouts.build()).build();
    }

    /** Returns {@code null} if nothing was overridden, so the caller can skip the merge entirely. */
    private static Environment toEnvironmentOverride(FitConnectProperties properties) {
        FitConnectProperties.BaseUrls baseUrls = properties.getBaseUrls();
        FitConnectProperties.Receiver receiver = properties.getReceiver();

        boolean anyOverride = baseUrls.getAuth() != null
                || baseUrls.getRouting() != null
                || !baseUrls.getSubmission().isEmpty()
                || baseUrls.getSelfServicePortal() != null
                || baseUrls.getDestination() != null
                || receiver.isAllowInsecurePublicKey()
                || receiver.isSkipSubmissionDataValidation()
                || receiver.isDisableAutoReject();
        if (!anyOverride) {
            return null;
        }

        Environment override = new Environment();
        override.setAuthBaseUrl(baseUrls.getAuth());
        override.setRoutingBaseUrl(baseUrls.getRouting());
        override.setSubmissionBaseUrls(baseUrls.getSubmission().isEmpty() ? null : baseUrls.getSubmission());
        override.setSelfServicePortalBaseUrl(baseUrls.getSelfServicePortal());
        override.setDestinationBaseUrl(baseUrls.getDestination());
        // Only ever force these true - like the CLI samples, there's no way
        // to force an environment's default back to false once set, only to
        // leave it alone (Boolean stays null -> merge() falls back to the
        // SDK default).
        override.setAllowInsecurePublicKey(receiver.isAllowInsecurePublicKey() ? Boolean.TRUE : null);
        override.setSkipSubmissionDataValidation(receiver.isSkipSubmissionDataValidation() ? Boolean.TRUE : null);
        override.setEnableAutoReject(receiver.isDisableAutoReject() ? Boolean.FALSE : null);
        return override;
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw missingProperty(property);
        }
    }

    private static FitConnectConfigurationException missingProperty(String property) {
        return new FitConnectConfigurationException("Required property '" + property + "' is not set.");
    }
}
