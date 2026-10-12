package com.gfi.ozg.fitko.swing.ui;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Logback appender behind the in-app {@link LogPanel}: keeps the last lines
 * in memory and forwards new ones to the panel once it exists.
 */
public class LogPanelAppender extends AppenderBase<ILoggingEvent> {

    private static final int MAX_LINES = 2000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final Deque<String> BUFFER = new ArrayDeque<>();
    private static Consumer<String> listener;

    @Override
    protected void append(ILoggingEvent event) {
        StringBuilder line = new StringBuilder()
                .append(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())))
                .append(' ').append(String.format("%-5s", event.getLevel()))
                .append(' ').append(shortName(event.getLoggerName()))
                .append(" - ").append(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            line.append("\n    ").append(t.getClassName()).append(": ").append(t.getMessage());
        }
        String text = line.toString();
        Consumer<String> current;
        synchronized (LogPanelAppender.class) {
            BUFFER.addLast(text);
            while (BUFFER.size() > MAX_LINES) {
                BUFFER.removeFirst();
            }
            current = listener;
        }
        if (current != null) {
            current.accept(text);
        }
    }

    /** Registers the panel and returns everything logged so far. */
    static synchronized List<String> attach(Consumer<String> newListener) {
        listener = newListener;
        return new ArrayList<>(BUFFER);
    }

    private static String shortName(String loggerName) {
        int dot = loggerName.lastIndexOf('.');
        return dot < 0 ? loggerName : loggerName.substring(dot + 1);
    }
}
