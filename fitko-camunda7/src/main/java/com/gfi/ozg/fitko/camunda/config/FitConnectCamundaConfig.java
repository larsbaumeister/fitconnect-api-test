package com.gfi.ozg.fitko.camunda.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * All configuration this process application needs.
 *
 * <p>No Spring: this is a plain CDI {@code @ApplicationScoped} bean. A value is
 * looked up in this order (first hit wins):
 * <ol>
 *   <li>a JVM system property with the key verbatim, e.g.
 *       {@code -Dfitconnect.destination-id=...};</li>
 *   <li>an environment variable, key upper-cased with {@code .} and {@code -}
 *       replaced by {@code _}, e.g. {@code FITCONNECT_DESTINATION_ID};</li>
 *   <li>an external properties file named by the {@code fitconnect.camunda.config}
 *       system property (or {@code FITCONNECT_CAMUNDA_CONFIG} env var);</li>
 *   <li>{@code application.properties} bundled on the classpath (template /
 *       defaults - keep real secrets out of it).</li>
 * </ol>
 *
 * <p>Tests construct it directly and call {@link #set(String, String)} instead
 * of relying on {@code @PostConstruct}.
 */
@ApplicationScoped
@Slf4j
public class FitConnectCamundaConfig {

    public static final String ENVIRONMENT = "fitconnect.environment";
    public static final String DESTINATION_ID = "fitconnect.destination-id";
    public static final String CLIENT_ID = "fitconnect.subscriber.client-id";
    public static final String CLIENT_SECRET = "fitconnect.subscriber.client-secret";
    public static final String SIGNING_KEY_PATH = "fitconnect.subscriber.signing-key-path";
    public static final String DECRYPTION_KEY_PATHS = "fitconnect.subscriber.decryption-key-paths";
    public static final String POLL_LIMIT = "fitconnect.poll.limit";
    public static final String POLL_CYCLE = "fitconnect.poll.cycle";

    private static final String EXTERNAL_CONFIG_KEY = "fitconnect.camunda.config";

    /** Explicit overrides, used by tests (and always consulted first after sys/env). */
    private final Map<String, String> overrides = new HashMap<>();

    private final Properties fileProperties = new Properties();

    @PostConstruct
    void load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                fileProperties.load(in);
            }
        } catch (IOException e) {
            log.warn("Could not read bundled application.properties", e);
        }

        String external = firstOf(System.getProperty(EXTERNAL_CONFIG_KEY), System.getenv("FITCONNECT_CAMUNDA_CONFIG"));
        if (external != null && !external.isBlank()) {
            Path path = Path.of(external.trim());
            try (InputStream in = Files.newInputStream(path)) {
                fileProperties.load(in);
                log.info("Loaded external FIT-Connect config from {}", path);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + EXTERNAL_CONFIG_KEY + " file " + path, e);
            }
        }
    }

    /** Test hook: force a value regardless of system properties / files. */
    public void set(String key, String value) {
        overrides.put(key, value);
    }

    public String environment() {
        return get(ENVIRONMENT, "TEST");
    }

    public String destinationId() {
        return required(DESTINATION_ID);
    }

    public String clientId() {
        return required(CLIENT_ID);
    }

    public String clientSecret() {
        return required(CLIENT_SECRET);
    }

    public String signingKeyPath() {
        return required(SIGNING_KEY_PATH);
    }

    public List<String> decryptionKeyPaths() {
        List<String> result = new ArrayList<>();
        for (String entry : required(DECRYPTION_KEY_PATHS).split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public int pollLimit() {
        return Integer.parseInt(get(POLL_LIMIT, "50").trim());
    }

    public String pollCycle() {
        return get(POLL_CYCLE, "R/PT5M");
    }

    String get(String key, String defaultValue) {
        String value = overrides.get(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        if (value == null) {
            value = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'));
        }
        if (value == null) {
            value = fileProperties.getProperty(key);
        }
        return value != null ? value : defaultValue;
    }

    private String required(String key) {
        String value = get(key, null);
        if (value == null || value.isBlank() || value.startsWith("CHANGE_ME")) {
            throw new IllegalStateException("Required FIT-Connect configuration '" + key + "' is not set");
        }
        return value.trim();
    }

    private static String firstOf(String a, String b) {
        return a != null ? a : b;
    }
}
