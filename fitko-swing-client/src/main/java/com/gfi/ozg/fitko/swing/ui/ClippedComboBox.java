package com.gfi.ozg.fitko.swing.ui;

import javax.swing.ComboBoxEditor;
import javax.swing.JComboBox;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;

/**
 * An editable combo box whose width does not grow with its (possibly very long) items, so it
 * never pushes its container wider. Long text is clipped in the field, shown in full as a
 * tooltip, and FlatLaf still widens the popup list to fit the longest item.
 */
public class ClippedComboBox<E> extends JComboBox<E> {

    /** Preferred width cap; the form's layout stretches the combo to the available width anyway. */
    private static final int MAX_PREFERRED_WIDTH = 200;

    private JTextComponent editorField;

    public ClippedComboBox() {
        setEditable(true);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        d.width = Math.min(d.width, MAX_PREFERRED_WIDTH);
        return d;
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension d = super.getMinimumSize();
        d.width = Math.min(d.width, MAX_PREFERRED_WIDTH);
        return d;
    }

    /** Called again by the look and feel on every theme switch with a fresh editor. */
    @Override
    public void setEditor(ComboBoxEditor editor) {
        super.setEditor(editor);
        Component c = editor == null ? null : editor.getEditorComponent();
        if (c instanceof JTextComponent field && field != editorField) {
            editorField = field;
            field.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> updateEditorToolTip(field)));
        }
    }

    private void updateEditorToolTip(JTextComponent field) {
        String text = field.getText();
        field.setToolTipText(text == null || text.isBlank() ? getToolTipText() : text);
    }
}
