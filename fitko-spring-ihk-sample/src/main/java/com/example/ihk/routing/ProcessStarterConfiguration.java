package com.example.ihk.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link NoopProcessStarter} as the default {@link ProcessStarter}
 * bean.
 *
 * <p>{@code @ConditionalOnMissingBean} deliberately lives on a {@code @Bean}
 * method in a {@code @Configuration} class here, not directly on {@code
 * NoopProcessStarter} as a {@code @Component} - the same convention
 * fitko-spring itself uses throughout (see its {@code
 * FitConnect*AutoConfiguration} classes). Conditions on a plain
 * component-scanned class are evaluated immediately as that class is parsed,
 * before every other class is known, so they can't reliably see a
 * consumer's own {@code ProcessStarter} bean declared elsewhere; a {@code
 * @Bean} method's condition is deferred until all configuration classes have
 * been parsed. Replace this default by declaring your own {@code @Bean} of
 * type {@link ProcessStarter}.
 */
@Configuration(proxyBeanMethods = false)
public class ProcessStarterConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProcessStarter.class)
    public ProcessStarter processStarter() {
        return new NoopProcessStarter();
    }
}
