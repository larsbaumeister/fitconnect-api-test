package com.gfi.ozg.fitko.swing.ui;

import javax.swing.table.DefaultTableCellRenderer;
import java.time.Instant;

/** Formats {@link Instant} cells in the local time zone. */
public class InstantCellRenderer extends DefaultTableCellRenderer {

    @Override
    protected void setValue(Object value) {
        setText(value instanceof Instant instant ? Ui.format(instant) : "");
    }
}
