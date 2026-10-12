package com.gfi.ozg.fitko.swing.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.SystemTray;
import java.awt.TrayIcon;

/** Desktop notifications through the system tray, where the platform has one. */
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private TrayIcon trayIcon;
    private boolean unavailable;

    public void notify(String caption, String text, boolean error) {
        TrayIcon icon = trayIcon();
        if (icon != null) {
            icon.displayMessage(caption, text, error ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
        }
    }

    private TrayIcon trayIcon() {
        if (trayIcon != null || unavailable) {
            return trayIcon;
        }
        try {
            if (!SystemTray.isSupported()) {
                unavailable = true;
                return null;
            }
            TrayIcon icon = new TrayIcon(Ui.appIcon(16), "FIT-Connect Sender");
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
        } catch (AWTException | RuntimeException e) {
            log.info("Desktop notifications unavailable: {}", e.getMessage());
            unavailable = true;
        }
        return trayIcon;
    }

    public void dispose() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }
}
