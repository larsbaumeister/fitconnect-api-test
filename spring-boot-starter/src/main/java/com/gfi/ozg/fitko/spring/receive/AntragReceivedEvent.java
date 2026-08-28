package com.gfi.ozg.fitko.spring.receive;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@link AntragPollingService} for every submission it downloads.
 * Handle it with a regular {@code @EventListener}:
 *
 * <pre>{@code
 * @Component
 * class GewerbeanmeldungHandler {
 *
 *     @EventListener
 *     void onAntrag(AntragReceivedEvent event) {
 *         ReceivedAntrag antrag = event.getAntrag();
 *         process(antrag.getDataAsString());
 *         antrag.accept();
 *     }
 * }
 * }</pre>
 *
 * <p>Listener methods run synchronously on the polling thread, in
 * registration order, before the next submission is fetched; make a listener
 * {@code @Async} if it does non-trivial work. If no listener calls {@link
 * ReceivedAntrag#accept()}/{@link ReceivedAntrag#reject}, {@code
 * fitconnect.receiver.default-outcome} decides what happens to the
 * submission next.
 */
public class AntragReceivedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final transient ReceivedAntrag antrag;

    public AntragReceivedEvent(Object source, ReceivedAntrag antrag) {
        super(source);
        this.antrag = antrag;
    }

    public ReceivedAntrag getAntrag() {
        return antrag;
    }
}
