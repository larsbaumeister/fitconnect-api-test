package com.example.ihk.processstarter;

/**
 * Extension point: one implementation per way of actually starting a
 * process - unlike a typical {@code @ConditionalOnMissingBean} extension
 * point (one implementation replacing a default), several {@code
 * ProcessStarter} beans are meant to coexist here. Which one handles a given
 * Antrag is decided per (tenant, Leistung) in {@code application.yaml} (see
 * {@code com.example.ihk.routing.AntragRoutingProperties}, by fully-qualified
 * class name) and resolved to the matching Spring bean by {@link
 * ProcessStarterLookup} - not injected directly.
 *
 * <p>Implementations live in {@code com.example.ihk.processstarter.impl} -
 * this sample ships two stubs there, {@code NoopProcessStarter} and {@code
 * LoggingProcessStarter}, both just logging. Add your own {@code @Component}
 * implementing this interface (in that package or any other - it just needs
 * to be a Spring bean), backed by your organization's own process-starting
 * library/Camunda client, and reference its fully-qualified class name from
 * {@code application.yaml} to use it.
 */
public interface ProcessStarter {

    /**
     * Starts the downstream process for {@code request}. Returning normally
     * means the submission is accepted.
     *
     * @throws ProcessStartRejectedException if the Antrag can never be
     *         processed - the submission is rejected with its {@code Problem}s
     * @throws RuntimeException any other failure is treated as transient -
     *         the submission stays on the delivery service and is retried
     */
    void start(ProcessStartRequest request);
}
