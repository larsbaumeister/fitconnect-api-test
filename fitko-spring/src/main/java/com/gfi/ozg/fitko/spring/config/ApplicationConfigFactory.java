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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
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
 *
 * <p>{@link #create} builds everything shared across the whole application
 * (sender config, environment/HTTP settings) but deliberately never attaches
 * a {@code subscriberConfig} - the SDK bakes one client-id/secret and one
 * signing/decryption key set into each {@code SubscriberClient} instance
 * (verified against {@code ClientFactory.createSubscriberClient}), so
 * supporting different keys per destination means one {@code SubscriberConfig}
 * (via {@link #createSubscriberConfig}) and one {@code ApplicationConfig}
 * (via {@link #withSubscriberConfig}) per destination, not one for the whole
 * app. {@link com.gfi.ozg.fitko.spring.autoconfigure.FitConnectReceiverAutoConfiguration}
 * does exactly that, once per {@link FitConnectProperties.Receiver.Destination}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApplicationConfigFactory {

    public static ApplicationConfig create(FitConnectProperties properties) {
        EnvironmentName environmentName = new EnvironmentName(properties.getEnvironment());

        ApplicationConfig.ApplicationConfigBuilder builder = ApplicationConfig.builder()
                .activeEnvironment(environmentName);

        if (properties.getSender().isEnabled()) {
            builder.senderConfig(toSenderConfig(properties.getSender()));
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

    /**
     * Returns a copy of {@code base} with {@code subscriberConfig} attached,
     * otherwise identical - everything else (environment, HTTP settings,
     * sender config, ...) is shared across every destination. There's no
     * SDK {@code withSubscriberConfig}/{@code toBuilder()} for this, so it
     * goes through {@link ApplicationConfig}'s full constructor.
     */
    public static ApplicationConfig withSubscriberConfig(ApplicationConfig base, SubscriberConfig subscriberConfig) {
        return new ApplicationConfig(
                base.getSenderConfig(),
                subscriberConfig,
                base.getActiveEnvironment(),
                base.getHttpConfig(),
                base.getAttachmentChunkingConfig(),
                base.getSubmissionDataSchemas(),
                base.getConcurrentAttachmentStreams(),
                base.getVirusScannerConfig(),
                base.getVirusScannerMode(),
                base.getEnvironments());
    }

    /**
     * Builds the {@link SubscriberConfig} for one {@link
     * FitConnectProperties.Receiver.Destination}: its own signing/decryption
     * keys, and its own client-id/client-secret if set, otherwise {@code
     * receiver}'s.
     */
    public static SubscriberConfig createSubscriberConfig(FitConnectProperties.Receiver receiver,
                                                            FitConnectProperties.Receiver.Destination destination) {
        String clientId = firstNonBlank(destination.getClientId(), receiver.getClientId());
        String clientSecret = firstNonBlank(destination.getClientSecret(), receiver.getClientSecret());
        requireText(clientId, describe(destination, "client-id"));
        requireText(clientSecret, describe(destination, "client-secret"));
        if (destination.getSigningKey() == null) {
            throw missingProperty(describe(destination, "signing-key"));
        }
        if (destination.getDecryptionKeys().isEmpty()) {
            throw missingProperty(describe(destination, "decryption-keys"));
        }
        List<JWK> decryptionKeys = new ArrayList<>(destination.getDecryptionKeys().size());
        for (Resource resource : destination.getDecryptionKeys()) {
            decryptionKeys.add(readJwk(resource, describe(destination, "decryption-keys")));
        }
        return SubscriberConfig.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .privateSigningKey(readJwk(destination.getSigningKey(), describe(destination, "signing-key")))
                .privateDecryptionKeys(decryptionKeys)
                .build();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** Names a destination's property for an error/exception message, since destinations aren't otherwise indexed. */
    private static String describe(FitConnectProperties.Receiver.Destination destination, String property) {
        String id = destination.getId() == null ? "<no id set>" : destination.getId().toString();
        return "fitconnect.receiver.destinations[id=" + id + "]." + property;
    }

    private static SenderConfig toSenderConfig(FitConnectProperties.Sender sender) {
        requireText(sender.getClientId(), "fitconnect.sender.client-id");
        requireText(sender.getClientSecret(), "fitconnect.sender.client-secret");
        return new SenderConfig(sender.getClientId(), sender.getClientSecret());
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
