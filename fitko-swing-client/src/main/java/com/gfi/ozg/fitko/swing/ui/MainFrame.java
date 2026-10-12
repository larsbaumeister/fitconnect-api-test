package com.gfi.ozg.fitko.swing.ui;

import com.formdev.flatlaf.FlatLaf;
import com.gfi.ozg.fitko.swing.FitConnectSwingClient;
import com.gfi.ozg.fitko.swing.model.AppSettings;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;
import com.gfi.ozg.fitko.swing.service.SenderService;
import com.gfi.ozg.fitko.swing.service.StatusPoller;
import com.gfi.ozg.fitko.swing.store.AppData;

import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** The main window: three tabs, a collapsible log, and a status bar. */
public class MainFrame extends JFrame implements AppContext.Navigator {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppContext ctx;
    private final JTabbedPane tabs = new JTabbedPane();
    private final ComposePanel compose;
    private final HistoryPanel history;
    private final ZustellpunktePanel zustellpunkte;
    private final LogPanel logPanel = new LogPanel();
    private final JSplitPane logSplit;
    private final JLabel messageLabel = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel();
    private final Notifier notifier = new Notifier();
    private final Timer saveSubmissions;
    private int lastLogDivider = -1;

    public MainFrame(AppContext ctx) {
        super("FIT-Connect Sender");
        this.ctx = ctx;
        ctx.navigator = this;
        setIconImages(List.of(Ui.appIcon(16), Ui.appIcon(32), Ui.appIcon(64)));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        // Status results arrive one by one; save them in one go.
        saveSubmissions = new Timer(400, e -> ctx.data.saveSubmissions());
        saveSubmissions.setRepeats(false);
        ctx.poller = new StatusPoller(ctx.sender, this::pendingRecords, this::onStatusResult);

        compose = new ComposePanel(ctx);
        history = new HistoryPanel(ctx);
        zustellpunkte = new ZustellpunktePanel(ctx);
        tabs.addTab("New submission", compose);
        tabs.addTab("Sent submissions", history);
        tabs.addTab("Zustellpunkte", zustellpunkte);
        tabs.setMnemonicAt(0, KeyEvent.VK_1);
        tabs.setMnemonicAt(1, KeyEvent.VK_2);
        tabs.setMnemonicAt(2, KeyEvent.VK_3);

        logSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logPanel);
        logSplit.setResizeWeight(1.0);
        logSplit.setBorder(BorderFactory.createEmptyBorder());
        logPanel.setVisible(false);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        statusBar.add(messageLabel, BorderLayout.CENTER);
        statusBar.add(summaryLabel, BorderLayout.EAST);

        getContentPane().add(logSplit, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(menuBar());

        ctx.data.addListener(kind -> {
            if (kind == AppData.Kind.SUBMISSIONS) {
                updateSummary();
            }
        });
        ctx.data.onWriteError(e -> SwingUtilities.invokeLater(() ->
                Ui.error(this, "Save failed", SenderService.describe(e))));
        updateSummary();
        restoreWindow();
        ctx.poller.setInterval(ctx.data.settings().pollIntervalSeconds);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(MainFrame.this::afterOpen);
            }
        });
    }

    private JMenuBar menuBar() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("Connections…", KeyStroke.getKeyStroke(KeyEvent.VK_K, shortcut),
                () -> new ConnectionsDialog(this, ctx).setVisible(true)));
        file.add(menuItem("Settings…", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, shortcut), () ->
                new SettingsDialog(this, ctx, () -> ctx.poller.setInterval(ctx.data.settings().pollIntervalSeconds)).setVisible(true)));
        file.addSeparator();
        file.add(menuItem("Export history as CSV…", null, history::exportCsv));
        file.addSeparator();
        file.add(menuItem("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcut), this::quit));
        bar.add(file);

        JMenu view = new JMenu("View");
        JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark theme", ctx.data.settings().darkTheme);
        dark.addActionListener(e -> setDarkTheme(dark.isSelected()));
        view.add(dark);
        JCheckBoxMenuItem showLog = new JCheckBoxMenuItem("Show log");
        showLog.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, shortcut));
        showLog.addActionListener(e -> setLogVisible(showLog.isSelected()));
        view.add(showLog);
        bar.add(view);

        JMenu help = new JMenu("Help");
        help.add(menuItem("Placeholders", null, () -> Ui.info(this, "Placeholders", """
                Placeholders in the payload are resolved freshly for every send:

                  ${uuid}     a new random UUID (one per occurrence)
                  ${now}      current timestamp, ISO-8601 with offset
                  ${date}     current date, ISO-8601
                  ${counter}  a running number, persisted across restarts
                  ${caseId}   the case id the submission is appended to

                Write $${x} for a literal ${x}.""")));
        help.add(menuItem("About", null, () -> Ui.info(this, "About", """
                FIT-Connect Sender - a dev tool for sending test submissions to any Zustellpunkt.

                Built on the FIT-Connect Java SDK.
                Data directory: """ + ctx.data.directory())));
        bar.add(help);
        return bar;
    }

    private static JMenuItem menuItem(String text, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private void setDarkTheme(boolean dark) {
        FitConnectSwingClient.applyTheme(dark);
        FlatLaf.updateUI();
        ctx.data.settings().darkTheme = dark;
        ctx.data.saveSettings();
    }

    private void setLogVisible(boolean visible) {
        if (!visible) {
            lastLogDivider = logSplit.getDividerLocation();
        }
        logPanel.setVisible(visible);
        if (visible) {
            logSplit.setDividerLocation(lastLogDivider > 0 ? lastLogDivider : (int) (logSplit.getHeight() * 0.7));
        }
    }

    // --- startup / shutdown -----------------------------------------------

    private void restoreWindow() {
        AppSettings s = ctx.data.settings();
        setSize(s.windowWidth, s.windowHeight);
        if (s.windowX >= 0 && s.windowY >= 0 && isOnScreen(s.windowX, s.windowY)) {
            setLocation(s.windowX, s.windowY);
        } else {
            setLocationRelativeTo(null);
        }
        if (s.windowMaximized) {
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
    }

    private static boolean isOnScreen(int x, int y) {
        for (var device : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(x + 40, y + 20)) {
                return true;
            }
        }
        return false;
    }

    private void afterOpen() {
        if (!ctx.data.loadWarnings().isEmpty()) {
            Ui.error(this, "Some data could not be loaded", String.join("\n\n", ctx.data.loadWarnings()));
        }
        boolean firstStart = ctx.data.connections().isEmpty() && ctx.data.zustellpunkte().isEmpty()
                && ctx.data.presets().isEmpty() && ctx.data.submissions().isEmpty();
        if (firstStart) {
            ctx.data.upsert(Preset.exampleGewerbeanmeldung());
        }
        if (ctx.data.connections().isEmpty()) {
            int choice = JOptionPane.showOptionDialog(this, """
                            Welcome! To send submissions you need:

                              1. a connection - the client id + secret of a sender client
                                 from the FIT-Connect Self-Service-Portal, and
                              2. a Zustellpunkt - the destination ID you want to send to.

                            Set up a connection now?""", "Welcome", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Add connection", "Later"}, "Add connection");
            if (choice != 0) {
                return;
            }
            new ConnectionsDialog(this, ctx).startNew().setVisible(true);
        }
        if (!ctx.data.connections().isEmpty() && ctx.data.zustellpunkte().isEmpty()
                && JOptionPane.showConfirmDialog(this, "Add your first Zustellpunkt now?", "Welcome",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            tabs.setSelectedComponent(zustellpunkte);
            Zustellpunkt template = new Zustellpunkt();
            template.connectionId = ctx.defaultConnection().id;
            Zustellpunkt added = zustellpunkte.addWithDialog(template);
            if (added != null) {
                compose(null, added, null);
            }
        }
    }

    private void quit() {
        AppSettings s = ctx.data.settings();
        s.windowMaximized = (getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        if (!s.windowMaximized) {
            s.windowX = getX();
            s.windowY = getY();
            s.windowWidth = getWidth();
            s.windowHeight = getHeight();
        }
        s.composeSplit = compose.dividerLocation();
        s.historySplit = history.dividerLocation();
        s.zustellpunkteSplit = zustellpunkte.dividerLocation();
        ctx.data.saveSettings();
        if (saveSubmissions.isRunning()) {
            saveSubmissions.stop();
            ctx.data.saveSubmissions();
        }
        ctx.poller.shutdown();
        ctx.data.flush();
        notifier.dispose();
        dispose();
        System.exit(0);
    }

    // --- status polling ---------------------------------------------------

    private List<SubmissionRecord> pendingRecords() {
        return ctx.data.submissions().stream().filter(r -> r.wasSent() && r.status.isPending()).toList();
    }

    private void onStatusResult(SubmissionRecord record, SenderService.StatusResult result) {
        if (!ctx.data.submissions().contains(record)) {
            return; // deleted meanwhile
        }
        RecordStatus before = record.status;
        boolean changed = result.applyTo(record);
        saveSubmissions.restart();
        if (result.error() != null) {
            message("Status check for " + record.submissionId + " failed: " + result.error().lines().findFirst().orElse(""));
            return;
        }
        if (changed) {
            history.statusChanged(record);
            String where = record.zustellpunktLabel != null ? record.zustellpunktLabel : String.valueOf(record.destinationId);
            String text = "Submission " + shortId(record.submissionId) + " → " + record.status + " (" + where + ")";
            message(text + (before == RecordStatus.UNKNOWN ? "" : ", was " + before));
            if (record.status.isFinal() && ctx.data.settings().desktopNotifications) {
                String detail = record.problems.isEmpty() ? where : where + "\n" + record.problems.get(0);
                notifier.notify("Submission " + record.status.name().toLowerCase(), detail, record.status == RecordStatus.REJECTED);
            }
        }
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private void updateSummary() {
        List<SubmissionRecord> records = ctx.data.submissions();
        long pending = records.stream().filter(r -> r.wasSent() && r.status.isPending()).count();
        long accepted = records.stream().filter(r -> r.status == RecordStatus.ACCEPTED).count();
        long rejected = records.stream().filter(r -> r.status == RecordStatus.REJECTED).count();
        long failed = records.stream().filter(r -> r.status == RecordStatus.SEND_FAILED).count();
        int interval = ctx.data.settings().pollIntervalSeconds;
        summaryLabel.setText(records.size() + " sent · " + pending + " pending · " + accepted + " accepted · "
                + rejected + " rejected" + (failed > 0 ? " · " + failed + " failed" : "")
                + "   |   auto-check " + (interval > 0 ? "every " + interval + " s" : "off"));
    }

    // --- Navigator --------------------------------------------------------

    @Override
    public void compose(Preset form, Zustellpunkt zustellpunkt, UUID caseId) {
        compose.compose(form, zustellpunkt, caseId);
        tabs.setSelectedComponent(compose);
    }

    @Override
    public void showInHistory(SubmissionRecord record) {
        tabs.setSelectedComponent(history);
        history.select(record);
    }

    @Override
    public void message(String message) {
        messageLabel.setText(LocalTime.now().format(TIME) + "  " + message);
        messageLabel.setToolTipText(message);
        updateSummary();
    }
}
