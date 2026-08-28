package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.submission.PublicService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Supports {@link AntragEventListener}. Delegates to the same {@link
 * ApplicationListenerMethodAdapter} a plain {@code @EventListener} method
 * gets, wrapped so a method with a non-empty {@link
 * AntragEventListener#serviceIds()} is only invoked for a submission whose
 * LeiKa service identifier is in that list - every other submission is
 * skipped without invoking the method at all, exactly as if the listener had
 * declared an {@code @EventListener(condition = ...)} for it.
 *
 * <p>Ordered ahead of Spring's {@code DefaultEventListenerFactory} (which
 * would otherwise also claim these methods, since it matches everything) so
 * {@code @AntragEventListener} methods are routed here.
 */
public class AntragEventListenerFactory implements EventListenerFactory, Ordered {

    /** Lower runs first; only needs to stay below {@code Ordered.LOWEST_PRECEDENCE}, the default factory's order. */
    @Getter
    @Setter
    private int order = 0;

    @Override
    public boolean supportsMethod(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, AntragEventListener.class);
    }

    @Override
    public ApplicationListener<?> createApplicationListener(String beanName, Class<?> type, Method method) {
        AntragEventListener annotation = AnnotatedElementUtils.getMergedAnnotation(method, AntragEventListener.class);
        Set<String> serviceIds = Set.of(annotation.serviceIds());
        return new ServiceFilteringListenerMethodAdapter(beanName, type, method, serviceIds);
    }

    private static final class ServiceFilteringListenerMethodAdapter extends ApplicationListenerMethodAdapter {

        private final Set<String> serviceIds;

        ServiceFilteringListenerMethodAdapter(String beanName, Class<?> targetClass, Method method, Set<String> serviceIds) {
            super(beanName, targetClass, method);
            this.serviceIds = serviceIds;
        }

        // Deliberately not overriding shouldHandle(ApplicationEvent): the
        // adapter's own dispatch path (processEvent) calls a *private*
        // shouldHandle(event, args) overload to evaluate an @EventListener
        // condition, never the public one - overriding it would silently do
        // nothing. onApplicationEvent is the actual polymorphic entry point
        // the event multicaster calls, so the filter has to sit here.
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (matchesConfiguredServices(event)) {
                super.onApplicationEvent(event);
            }
        }

        private boolean matchesConfiguredServices(ApplicationEvent event) {
            if (serviceIds.isEmpty()) {
                return true;
            }
            if (!(event instanceof AntragReceivedEvent antragEvent)) {
                return false;
            }
            PublicService serviceType = antragEvent.getAntrag().getServiceType();
            return serviceType != null && serviceIds.contains(serviceType.getIdentifier());
        }
    }
}
