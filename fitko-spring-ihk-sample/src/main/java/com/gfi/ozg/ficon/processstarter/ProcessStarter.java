package com.gfi.ozg.ficon.processstarter;

/**
 * Extension point: one implementation per way of actually starting a
 * process - unlike a typical {@code @ConditionalOnMissingBean} extension
 * point (one implementation replacing a default), several {@code
 * ProcessStarter} beans are meant to coexist here. Which one handles a given
 * Antrag is decided per (tenant, Leistung) in {@code application.yaml} (see
 * {@code com.gfi.ozg.ficon.processstarter.ProcessStarterRoutingProperties}, by fully-qualified
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
     * Starts the downstream process for {@code request}, including handing
     * over its attachments. Called by {@code AntragReceiveListener} inside a
     * transaction (see {@code SubmissionInbox#startProcess}), possibly more
     * than once for the same submission after a failure - an implementation
     * talking to a remote system must be idempotent on the submission id.
     *
     * @return what was started - recorded in the inbox, then the submission
     *         is accepted. Never {@code null}: that is treated as a failure.
     * @throws ProcessStartRejectedException if the Antrag can never be
     *         processed - the submission is rejected with its problems
     * @throws RuntimeException any other failure is treated as transient -
     *         the submission stays on the delivery service and is retried on
     *         a later poll cycle
     */
    StartedProcess start(ProcessStartRequest request);
}
