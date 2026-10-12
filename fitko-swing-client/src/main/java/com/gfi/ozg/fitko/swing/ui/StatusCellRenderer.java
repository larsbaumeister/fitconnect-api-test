package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.RecordStatus;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Font;

/** Renders the status column as a coloured badge. */
public class StatusCellRenderer extends DefaultTableCellRenderer {

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        if (value instanceof RecordStatus status) {
            label.setText(status.name().replace('_', ' '));
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            if (!isSelected) {
                label.setBackground(Ui.statusColor(status));
                label.setForeground(table.getForeground());
            }
        }
        return label;
    }
}
