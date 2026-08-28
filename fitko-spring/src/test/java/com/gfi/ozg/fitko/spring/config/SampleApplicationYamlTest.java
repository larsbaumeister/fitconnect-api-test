package com.gfi.ozg.fitko.spring.config;

import com.gfi.ozg.fitko.spring.FitConnectProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@code docs/application.yaml} - the sample config shown in the
 * project's documentation - against the real {@link FitConnectProperties}
 * the same way a consuming application's {@code application.yml} would, so
 * the sample can't silently drift out of sync with the actual property names
 * (a typo/rename here would otherwise only be caught by a user copy-pasting
 * the sample and hitting an "unknown property" surprise).
 */
class SampleApplicationYamlTest {

    private static final Path SAMPLE_YAML = Path.of("docs/application.yaml");
    private static final UUID DESTINATION_A = UUID.fromString("9f6bb611-df46-494a-9a98-a253f1362dc7");
    private static final UUID DESTINATION_B = UUID.fromString("2b7e8f2a-6e0a-4c1a-8f0a-7e6c9a2b1234");

    @Test
    void bindsCleanlyAgainstFitConnectProperties() throws Exception {
        assertThat(Files.exists(SAMPLE_YAML))
                .as("docs/application.yaml (run tests from the project root)")
                .isTrue();

        FitConnectProperties properties = bind(SAMPLE_YAML);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getEnvironment()).isEqualTo("TEST");

        assertThat(properties.getSender().isEnabled()).isTrue();
        assertThat(properties.getSender().getClientId()).isEqualTo("sender-id");
        assertThat(properties.getSender().getClientSecret()).isEqualTo("sender-secret");

        assertThat(properties.getReceiver().isEnabled()).isTrue();
        assertThat(properties.getReceiver().getClientId()).isEqualTo("receiver-id");
        assertThat(properties.getReceiver().getClientSecret()).isEqualTo("receiver-secret");
        assertThat(properties.getReceiver().getDefaultOutcome().name()).isEqualTo("LEAVE");
        assertThat(properties.getReceiver().isAllowInsecurePublicKey()).isFalse();
        assertThat(properties.getReceiver().isSkipSubmissionDataValidation()).isFalse();
        assertThat(properties.getReceiver().isDisableAutoReject()).isFalse();

        List<FitConnectProperties.Receiver.Destination> destinations = properties.getReceiver().getDestinations();
        assertThat(destinations).hasSize(2);

        FitConnectProperties.Receiver.Destination destinationA = destinations.get(0);
        assertThat(destinationA.getId()).isEqualTo(DESTINATION_A);
        assertThat(destinationA.getSigningKey()).isNotNull();
        assertThat(destinationA.getDecryptionKeys()).hasSize(1);
        assertThat(destinationA.getClientId()).isNull(); // falls back to receiver.client-id

        FitConnectProperties.Receiver.Destination destinationB = destinations.get(1);
        assertThat(destinationB.getId()).isEqualTo(DESTINATION_B);
        assertThat(destinationB.getSigningKey()).isNotNull();
        assertThat(destinationB.getDecryptionKeys()).hasSize(1);
        assertThat(destinationB.getClientId()).isEqualTo("ihk-b-id");
        assertThat(destinationB.getClientSecret()).isEqualTo("ihk-b-secret");

        assertThat(properties.getReceiver().getPolling().isEnabled()).isTrue();
        assertThat(properties.getReceiver().getPolling().getInitialDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getReceiver().getPolling().getInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getReceiver().getPolling().getLimit()).isEqualTo(100);

        assertThat(properties.getHttp().getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getHttp().getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getHttp().getWriteTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    /** Loads the YAML file and binds it to {@link FitConnectProperties}, resolving its {@code ${...}} placeholders against a fake environment. */
    private static FitConnectProperties bind(Path yamlFile) throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yaml", new FileSystemResource(yamlFile));

        Map<String, Object> fakeEnvVars = new HashMap<>();
        fakeEnvVars.put("FITCONNECT_SENDER_CLIENT_ID", "sender-id");
        fakeEnvVars.put("FITCONNECT_SENDER_CLIENT_SECRET", "sender-secret");
        fakeEnvVars.put("FITCONNECT_RECEIVER_CLIENT_ID", "receiver-id");
        fakeEnvVars.put("FITCONNECT_RECEIVER_CLIENT_SECRET", "receiver-secret");
        fakeEnvVars.put("FITCONNECT_IHK_B_CLIENT_ID", "ihk-b-id");
        fakeEnvVars.put("FITCONNECT_IHK_B_CLIENT_SECRET", "ihk-b-secret");

        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("fakeEnv", fakeEnvVars));
        loaded.forEach(sources::addLast);

        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("fitconnect", FitConnectProperties.class).get();
    }
}
