package com.gfi.ozg.fitko.swing.model;

/** UI and app settings that aren't connections, Zustellpunkte or presets. */
public class AppSettings {

    /** Seconds between automatic status checks of pending submissions; 0 = off. */
    public int pollIntervalSeconds = 30;
    public boolean darkTheme;
    public boolean desktopNotifications = true;
    /** Running number behind the {@code ${counter}} placeholder. */
    public long counter;
    public String lastPresetId;
    public String lastDirectory;

    public int windowX = -1;
    public int windowY = -1;
    public int windowWidth = 1280;
    public int windowHeight = 840;
    public boolean windowMaximized;
    public int historySplit = -1;
    public int zustellpunkteSplit = -1;
    public int composeSplit = -1;
}
