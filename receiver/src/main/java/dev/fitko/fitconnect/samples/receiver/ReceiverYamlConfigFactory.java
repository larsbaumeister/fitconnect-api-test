package dev.fitko.fitconnect.samples.receiver;

import dev.fitko.fitconnect.samples.common.config.YamlWriter;

/**
 * Renders {@link ReceiverOptions} into the YAML document expected by
 * {@code ApplicationConfigLoader.loadConfigFromYamlString(...)}.
 */
final class ReceiverYamlConfigFactory {

    private ReceiverYamlConfigFactory() {
    }

    static String toYaml(ReceiverOptions options) {
        YamlWriter yaml = new YamlWriter();
        yaml.line(0, "subscriberConfig:");
        yaml.keyValue(1, "clientId", options.getClientId());
        yaml.keyValue(1, "clientSecret", options.getClientSecret());
        yaml.keyValue(1, "privateSigningKeyPath", options.getSigningKeyPath());
        yaml.quotedList(1, "privateDecryptionKeyPaths", options.getDecryptionKeyPaths());
        yaml.keyValue(0, "activeEnvironment", options.getEnvironment());

        options.getEnvironmentOverrides().writeTo(yaml, options.getEnvironment());
        options.getLocalSchemas().writeTo(yaml);
        options.getHttpTimeouts().writeTo(yaml);

        return yaml.toString();
    }
}
