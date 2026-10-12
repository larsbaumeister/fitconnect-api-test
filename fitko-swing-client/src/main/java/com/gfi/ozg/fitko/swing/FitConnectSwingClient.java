package com.gfi.ozg.fitko.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.gfi.ozg.fitko.swing.service.DestinationInfoService;
import com.gfi.ozg.fitko.swing.service.SenderClients;
import com.gfi.ozg.fitko.swing.service.SenderService;
import com.gfi.ozg.fitko.swing.store.AppData;
import com.gfi.ozg.fitko.swing.ui.AppContext;
import com.gfi.ozg.fitko.swing.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point. Data lives in {@code ~/.fitconnect-ui} (override with
 * {@code -Dfitconnect.ui.home=<dir>}).
 */
public final class FitConnectSwingClient {

    private FitConnectSwingClient() {
    }

    public static void main(String[] args) {
        avoidNativeLibraryOnNetworkPath();
        AppData data = new AppData(AppData.defaultDirectory()).load();
        SwingUtilities.invokeLater(() -> {
            applyTheme(data.settings().darkTheme);
            SenderService sender = new SenderService(new SenderClients(), id -> data.connection(id).orElse(null));
            AppContext ctx = new AppContext(data, sender, new DestinationInfoService());
            new MainFrame(ctx).setVisible(true);
        });
    }

    /**
     * FlatLaf extracts its native helper (Windows title bar) next to the jar.
     * Started from a UNC path such as {@code \\wsl.localhost\...} that fails
     * with "URI has an authority component" - harmless but noisy, so skip the
     * native library there.
     */
    private static void avoidNativeLibraryOnNetworkPath() {
        try {
            java.net.URI location = FitConnectSwingClient.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            if (location.getAuthority() != null && System.getProperty("flatlaf.useNativeLibrary") == null) {
                System.setProperty("flatlaf.useNativeLibrary", "false");
            }
        } catch (Exception ignored) {
            // no code source - nothing to decide
        }
    }

    /**
     * FlatLaf light/dark; if FlatLaf can't initialise (it logs why - e.g. no
     * fonts configured on a bare Linux), falls back to Swing's built-in look
     * so the app still opens.
     */
    public static void applyTheme(boolean dark) {
        boolean ok = dark ? FlatDarkLaf.setup() : FlatLightLaf.setup();
        if (!ok) {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception e) {
                throw new IllegalStateException("No usable Swing look and feel", e);
            }
        }
    }
}
