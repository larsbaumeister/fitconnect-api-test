package com.example.ihk.routing;

/**
 * Extension point: actually starts the process {@link AntragProcessResolver}
 * decided on. This sample ships only {@link NoopProcessStarter} (logs and
 * does nothing) - declare your own {@code @Bean} of this type, backed by
 * your organization's own process-starting library/Camunda client, to
 * replace it ({@code @ConditionalOnMissingBean} on the default picks your
 * bean up automatically, no further wiring needed - the same extension-point
 * convention fitko-spring itself uses throughout).
 */
public interface ProcessStarter {

    void start(ProcessStartRequest request);
}
