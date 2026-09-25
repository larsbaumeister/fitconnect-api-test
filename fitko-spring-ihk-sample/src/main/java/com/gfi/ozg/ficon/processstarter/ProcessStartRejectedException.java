package com.gfi.ozg.ficon.processstarter;

/**
 * Thrown from {@link ProcessStarter#start} when an Antrag can <em>never</em>
 * be processed (e.g. its content fails a business check) - {@code
 * AntragDispatcher} then marks it {@code REJECTED} and stops retrying.
 *
 * <p>The submission is already accepted on FIT-Connect at this point, so
 * this is <em>not</em> a FIT-Connect reject: nothing is sent to the sender.
 * Telling the applicant is a separate step, typically a reply using {@code
 * InboxSubmission.getReplyEncryptionKey()}.
 *
 * <p>Only for permanent failures. Any other exception from {@code start}
 * (Camunda unreachable, a timeout, ...) counts as transient and is retried.
 */
public class ProcessStartRejectedException extends RuntimeException {

    public ProcessStartRejectedException(String message) {
        super(message);
    }

    public ProcessStartRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
