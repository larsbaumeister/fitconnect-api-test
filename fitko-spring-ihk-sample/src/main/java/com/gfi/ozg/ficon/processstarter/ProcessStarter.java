package com.gfi.ozg.ficon.processstarter;

/**
 * Extension point: one implementation per way of actually starting a
 * process - unlike a typical {@code @ConditionalOnMissingBean} extension
 * point (one implementation replacing a default), several {@code
 * ProcessStarter} beans are meant to coexist here. Which one handles a given
 * Antrag is decided per (tenant, Leistung) in {@code application.yaml} (see
 * {@code com.gfi.ozg.ficon.routing.AntragRoutingProperties}, by fully-qualified
 * class name) and resolved to the matching Spring bean by {@link
 * ProcessStarterLookup} - not injected directly.
 *
 * <p>Implementations live in {@code com.gfi.ozg.ficon.processstarter.impl} -
 * this sample ships two stubs there, {@code NoopProcessStarter} and {@code
 * LoggingProcessStarter}, both just logging. Add your own {@code @Component}
 * implementing this interface (in that package or any other - it just needs
 * to be a Spring bean), backed by your organization's own process-starting
 * library/Camunda client, and reference its fully-qualified class name from
 * {@code application.yaml} to use it.
 */
public interface ProcessStarter {

    /**
     * Starts the downstream process for {@code request}. Called by {@code
     * AntragDispatcher} inside a transaction, possibly more than once for the
     * same submission (after a failure) - see {@link ProcessStartRequest}.
     *
     * @return what was started - stored on the submission together with the
     *         start time. Never {@code null}: that is treated as a failure.
     * @throws ProcessStartRejectedException if the Antrag can never be
     *         processed - it is marked {@code REJECTED} and not retried
     * @throws RuntimeException any other failure is treated as transient -
     *         retried with backoff, {@code FAILED} after {@code
     *         antrag-dispatch.max-attempts}
     */
    StartedProcess start(ProcessStartRequest request);
}
