package com.gfi.ozg.fitko.spring.receive;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@link SubmissionPollingService} for every submission it downloads.
 * Handle it with a regular {@code @EventListener}:
 *
 * <pre>{@code
 * @Component
 * class GewerbeanmeldungHandler {
 *
 *     @EventListener
 *     void onSubmission(SubmissionReceivedEvent event) {
 *         IncomingSubmission submission = event.getSubmission();
 *         process(submission.getDataAsString());
 *         submission.accept();
 *     }
 * }
 * }</pre>
 *
 * <p>Listener methods run synchronously on the polling thread, in
 * registration order, before the next submission is fetched; make a listener
 * {@code @Async} if it does non-trivial work. If no listener calls {@link
 * IncomingSubmission#accept()}/{@link IncomingSubmission#reject}, {@code
 * fitconnect.receiver.default-outcome} decides what happens to the
 * submission next.
 *
 * <p>Use {@link SubmissionEventListener} instead of {@code @EventListener} to
 * only receive submissions for specific LeiKa services, e.g. one handler per
 * Leistung in an application that receives several.
 */
public class SubmissionReceivedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final transient IncomingSubmission submission;

    public SubmissionReceivedEvent(Object source, IncomingSubmission submission) {
        super(source);
        this.submission = submission;
    }

    public IncomingSubmission getSubmission() {
        return submission;
    }
}
