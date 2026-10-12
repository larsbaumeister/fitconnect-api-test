package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.AppSettings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;

/** Poll interval, notifications, data directory. */
public class SettingsDialog extends JDialog {

    public SettingsDialog(Window owner, AppContext ctx, Runnable onSaved) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        AppSettings settings = ctx.data.settings();

        JSpinner pollInterval = new JSpinner(new SpinnerNumberModel(settings.pollIntervalSeconds, 0, 3600, 5));
        JCheckBox notifications = new JCheckBox("Desktop notification when a submission is accepted or rejected",
                settings.desktopNotifications);
        JLabel directory = new JLabel(ctx.data.directory().toString());
        JButton open = new JButton("Open folder");
        open.setEnabled(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
        open.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(ctx.data.directory().toFile());
            } catch (IOException | RuntimeException ex) {
                Ui.error(this, "Open folder", ex.getMessage());
            }
        });

        Form form = new Form()
                .row("Status check every (s)", pollInterval, new JLabel("0 = off"))
                .full(notifications, false)
                .row("Data directory", directory, open)
                .full(new JLabel("<html><small>Override with <code>-Dfitconnect.ui.home=&lt;dir&gt;</code>. "
                        + "connections.json holds client secrets - it is readable by you only.</small></html>"), false);
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            settings.pollIntervalSeconds = (Integer) pollInterval.getValue();
            settings.desktopNotifications = notifications.isSelected();
            ctx.data.saveSettings();
            onSaved.run();
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }
}
