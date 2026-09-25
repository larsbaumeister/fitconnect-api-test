package com.gfi.ozg.ficon.processstarter;

import com.gfi.ozg.ficon.routing.AntragRoutingProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a configured fully-qualified class name (see {@link
 * AntragRoutingProperties#getProcessStarterByTenant()}/{@link
 * AntragRoutingProperties#getDefaultProcessStarterClass()}) to the actual
 * Spring-managed {@link ProcessStarter} bean of that type.
 *
 * <p>Deliberately goes through {@link ApplicationContext#getBean(Class)}
 * rather than instantiating the class by hand (e.g. via reflection) - every
 * {@link ProcessStarter} implementation stays a normal Spring bean, so it
 * can constructor-inject whatever it needs (a Camunda {@code RuntimeService},
 * an HTTP client, ...) and is container-managed like everything else in this
 * application.
 *
 * <p><b>Fails fast at startup, not on the first matching submission:</b>
 * {@link #validateConfiguredClasses()} resolves (and thereby caches) every
 * class name actually referenced in {@code antrag-routing.*} as soon as the
 * context is up - a typo'd class name or a class that isn't a Spring bean is
 * a configuration error and should surface at deploy time, not mid-poll-cycle
 * (same "fail fast" principle fitko-spring itself follows for its own
 * config).
 */
@Component
public class ProcessStarterLookup {

    private final ApplicationContext context;
    private final AntragRoutingProperties properties;
    private final Map<String, ProcessStarter> cache = new ConcurrentHashMap<>();

    public ProcessStarterLookup(ApplicationContext context, AntragRoutingProperties properties) {
        this.context = context;
        this.properties = properties;
    }

    @PostConstruct
    void validateConfiguredClasses() {
        Set<String> configured = new LinkedHashSet<>();
        properties.getProcessStarterByTenant().values().forEach(byLeistung -> configured.addAll(byLeistung.values()));
        if (properties.getDefaultProcessStarterClass() != null) {
            configured.add(properties.getDefaultProcessStarterClass());
        }
        configured.forEach(this::resolve);
    }

    public ProcessStarter resolve(String className) {
        return cache.computeIfAbsent(className, this::load);
    }

    private ProcessStarter load(String className) {
        Class<?> rawClass;
        try {
            rawClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "antrag-routing configured ProcessStarter class '" + className + "' was not found on the classpath", e);
        }
        if (!ProcessStarter.class.isAssignableFrom(rawClass)) {
            throw new IllegalStateException(
                    "antrag-routing configured class '" + className + "' does not implement ProcessStarter");
        }
        Class<? extends ProcessStarter> processStarterClass = rawClass.asSubclass(ProcessStarter.class);
        try {
            return context.getBean(processStarterClass);
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalStateException(
                    "antrag-routing configured ProcessStarter class '" + className + "' has no matching Spring bean"
                            + " - is it annotated @Component (or declared via a @Bean method)?", e);
        }
    }
}
