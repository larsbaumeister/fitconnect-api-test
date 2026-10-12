package com.gfi.ozg.fitko.swing.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** A two-column label/field form on a {@link GridBagLayout}. */
public class Form extends JPanel {

    private int row;

    public Form() {
        super(new GridBagLayout());
    }

    /** Adds {@code label: field [extra]}; {@code extra} may be {@code null}. */
    public Form row(String label, JComponent field, JComponent extra) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        JLabel jLabel = new JLabel(label);
        jLabel.setLabelFor(field);
        add(jLabel, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = extra == null ? 2 : 1;
        add(field, c);

        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.gridwidth = 1;
            add(extra, c);
        }
        row++;
        return this;
    }

    public Form row(String label, JComponent field) {
        return row(label, field, null);
    }

    /** A component spanning all columns; {@code grow} lets it take the remaining height. */
    public Form full(JComponent component, boolean grow) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = grow ? 1 : 0;
        c.fill = grow ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 4, 3, 4);
        add(component, c);
        return this;
    }

    /** Pushes everything above it to the top. */
    public Form filler() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row++;
        c.weighty = 1;
        add(new JLabel(), c);
        return this;
    }
}
