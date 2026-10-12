package com.gfi.ozg.fitko.swing.ui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A {@link DocumentListener} that runs one action for every kind of change. */
public final class SimpleDocumentListener implements DocumentListener {

    private final Runnable action;

    private SimpleDocumentListener(Runnable action) {
        this.action = action;
    }

    public static SimpleDocumentListener of(Runnable action) {
        return new SimpleDocumentListener(action);
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        action.run();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        action.run();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        action.run();
    }
}
