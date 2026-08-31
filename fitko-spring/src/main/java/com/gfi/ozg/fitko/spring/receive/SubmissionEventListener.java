package com.gfi.ozg.fitko.spring.receive;

import org.springframework.context.event.EventListener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a listener for {@link SubmissionReceivedEvent}, optionally
 * restricted to one or more LeiKa service identifiers (Leistungskennung,
 * e.g. {@code urn:de:fim:leika:leistung:99050035001000} - the same value
 * {@code event.getSubmission().getServiceType().getIdentifier()} returns).
 *
 * <p>Leave {@link #serviceIds()} empty (the default) to receive every
 * incoming submission regardless of which service it was submitted for:
 *
 * <pre>{@code
 * @SubmissionEventListener
 * void onAnySubmission(SubmissionReceivedEvent event) { ... }
 * }</pre>
 *
 * <p>Set it to only handle specific services - other listeners still see the
 * rest:
 *
 * <pre>{@code
 * @SubmissionEventListener(serviceIds = "urn:de:fim:leika:leistung:99050035001000")
 * void onGewerbeanmeldung(SubmissionReceivedEvent event) { ... }
 * }</pre>
 *
 * <p>A meta-annotated {@link EventListener}: everything that works on a
 * regular {@code @EventListener} method (return-value republishing, {@code
 * @Async}, {@code @Order}, a narrower parameter type instead of the raw
 * event, ...) works here too. Requires {@link SubmissionEventListenerFactory} to
 * be registered, which the receiver auto-configuration does automatically.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@EventListener(SubmissionReceivedEvent.class)
public @interface SubmissionEventListener {

    /**
     * LeiKa service identifiers this listener wants to receive submissions for.
     * Leave empty (the default) to receive every submission, regardless of service.
     */
    String[] serviceIds() default {};
}
