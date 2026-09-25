package com.gfi.ozg.ficon.processstarter;

import com.gfi.ozg.ficon.processstarter.impl.LoggingProcessStarter;
import com.gfi.ozg.ficon.processstarter.impl.NoopProcessStarter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessStarterLookupTest {

    private static final String NOOP = NoopProcessStarter.class.getName();
    private static final String LOGGING = LoggingProcessStarter.class.getName();

    @Test
    void resolvesAConfiguredClassNameToTheMatchingSpringBean() {
        NoopProcessStarter bean = new NoopProcessStarter();
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(NoopProcessStarter.class)).thenReturn(bean);

        ProcessStarterLookup lookup = new ProcessStarterLookup(context, new ProcessStarterRoutingProperties());

        assertThat(lookup.resolve(NOOP)).isSameAs(bean);
    }

    @Test
    void cachesTheResolvedBeanAcrossCalls() {
        NoopProcessStarter bean = new NoopProcessStarter();
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(NoopProcessStarter.class)).thenReturn(bean);
        ProcessStarterLookup lookup = new ProcessStarterLookup(context, new ProcessStarterRoutingProperties());

        lookup.resolve(NOOP);
        lookup.resolve(NOOP);

        verify(context, times(1)).getBean(NoopProcessStarter.class);
    }

    @Test
    void failsWithAClearErrorForAClassThatDoesNotExist() {
        ProcessStarterLookup lookup = new ProcessStarterLookup(mock(ApplicationContext.class), new ProcessStarterRoutingProperties());

        assertThatThrownBy(() -> lookup.resolve("com.gfi.ozg.ficon.processstarter.NoSuchClass"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found on the classpath");
    }

    @Test
    void failsWithAClearErrorForAClassThatDoesNotImplementProcessStarter() {
        ProcessStarterLookup lookup = new ProcessStarterLookup(mock(ApplicationContext.class), new ProcessStarterRoutingProperties());

        assertThatThrownBy(() -> lookup.resolve("java.lang.String"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not implement ProcessStarter");
    }

    @Test
    void failsWithAClearErrorWhenTheClassHasNoMatchingSpringBean() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(NoopProcessStarter.class)).thenThrow(new NoSuchBeanDefinitionException(NoopProcessStarter.class));
        ProcessStarterLookup lookup = new ProcessStarterLookup(context, new ProcessStarterRoutingProperties());

        assertThatThrownBy(() -> lookup.resolve(NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no matching Spring bean");
    }

    @Test
    void validateConfiguredClassesEagerlyResolvesEveryClassNameReferencedInConfig() {
        NoopProcessStarter noop = new NoopProcessStarter();
        LoggingProcessStarter logging = new LoggingProcessStarter();
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(NoopProcessStarter.class)).thenReturn(noop);
        when(context.getBean(LoggingProcessStarter.class)).thenReturn(logging);

        ProcessStarterRoutingProperties properties = new ProcessStarterRoutingProperties();
        properties.setProcessStarterByTenant(Map.of(
                "101-aachen", Map.of("urn:de:fim:leika:leistung:99050035001000", LOGGING),
                "133-hannover", Map.of("urn:de:fim:leika:leistung:99050035001000", NOOP)));
        properties.setDefaultProcessStarterClass(NOOP);

        ProcessStarterLookup lookup = new ProcessStarterLookup(context, properties);
        lookup.validateConfiguredClasses();

        // Both already resolved during validation, so a later resolve() is a pure cache hit.
        assertThat(lookup.resolve(NOOP)).isSameAs(noop);
        assertThat(lookup.resolve(LOGGING)).isSameAs(logging);
        verify(context, times(1)).getBean(NoopProcessStarter.class);
        verify(context, times(1)).getBean(LoggingProcessStarter.class);
    }

    @Test
    void validateConfiguredClassesFailsFastOnABadEntry() {
        ProcessStarterRoutingProperties properties = new ProcessStarterRoutingProperties();
        properties.setDefaultProcessStarterClass("com.gfi.ozg.ficon.processstarter.NoSuchClass");
        ProcessStarterLookup lookup = new ProcessStarterLookup(mock(ApplicationContext.class), properties);

        assertThatThrownBy(lookup::validateConfiguredClasses).isInstanceOf(IllegalStateException.class);
    }
}
