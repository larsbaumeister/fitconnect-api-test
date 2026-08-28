package com.gfi.ozg.fitko.sender;

import com.gfi.ozg.fitko.common.config.YamlWriter;

/**
 * Renders {@link SenderOptions} into the YAML document expected by
 * {@code ApplicationConfigLoader.loadConfigFromYamlString(...)}. Building the
 * config this way lets the SDK itself take care of environment defaults and
 * validation instead of duplicating that logic here.
 */
final class SenderYamlConfigFactory {

    private SenderYamlConfigFactory() {
    }

    static String toYaml(SenderOptions options) {
        YamlWriter yaml = new YamlWriter();
        yaml.line(0, "senderConfig:");
        yaml.keyValue(1, "clientId", options.getClientId());
        yaml.keyValue(1, "clientSecret", options.getClientSecret());
        yaml.keyValue(0, "activeEnvironment", options.getEnvironment());

        options.getEnvironmentOverrides().writeTo(yaml, options.getEnvironment());
        options.getLocalSchemas().writeTo(yaml);
        options.getHttpTimeouts().writeTo(yaml);

        return yaml.toString();
    }
}
