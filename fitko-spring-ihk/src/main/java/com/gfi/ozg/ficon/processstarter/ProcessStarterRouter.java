package com.gfi.ozg.ficon.processstarter;

import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which {@link ProcessStarter} handles an Antrag: {@code
 * antrag-routing.process-starter-by-tenant.<tenant>.<LeiKa-Schluessel>} names
 * a fully-qualified {@code ProcessStarter} class, falling back to {@code
 * antrag-routing.default-process-starter-class}. Every configured class must
 * be a Spring bean - checked at startup, so a typo fails the start instead of
 * the first matching Antrag.
 */
@Component
public class ProcessStarterRouter {

    /**
     * @param processStarterByTenant tenant -> LeiKa-Schluessel URN -> class name;
     *                               write URN keys in brackets in YAML
     *                               ({@code "[urn:de:fim:leika:leistung:...]"}),
     *                               otherwise Spring drops the colons
     * @param defaultProcessStarterClass for every unmapped (tenant, Leistung);
     *                               if unset, such an Antrag is left on the
     *                               delivery service
     */
    @ConfigurationProperties("antrag-routing")
    public record Properties(@DefaultValue Map<String, Map<String, String>> processStarterByTenant,
                             String defaultProcessStarterClass) {
    }

    private final Properties properties;
    private final Map<String, ProcessStarter> processStarters = new HashMap<>();

    public ProcessStarterRouter(Properties properties, ApplicationContext context) {
        this.properties = properties;
        properties.processStarterByTenant().values().forEach(byLeistung ->
                byLeistung.values().forEach(className -> register(className, context)));
        if (properties.defaultProcessStarterClass() != null) {
            register(properties.defaultProcessStarterClass(), context);
        }
    }

    /** @return the configured class name, or empty if neither a mapping nor a default is configured */
    public Optional<String> processStarterClassFor(String tenant, String leikaSchluessel) {
        String mapped = properties.processStarterByTenant().getOrDefault(tenant, Map.of()).get(leikaSchluessel);
        return Optional.ofNullable(mapped != null ? mapped : properties.defaultProcessStarterClass());
    }

    /** @param className a name returned by {@link #processStarterClassFor} */
    public ProcessStarter processStarter(String className) {
        return processStarters.get(className);
    }

    private void register(String className, ApplicationContext context) {
        processStarters.computeIfAbsent(className, name -> {
            try {
                return context.getBean(Class.forName(name).asSubclass(ProcessStarter.class));
            } catch (ClassNotFoundException | ClassCastException | BeansException e) {
                throw new IllegalStateException("antrag-routing: '" + name
                        + "' is not a ProcessStarter class with a Spring bean", e);
            }
        });
    }
}
