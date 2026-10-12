package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.service.SenderService;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/** Small Swing helpers shared by the panels. */
public final class Ui {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Ui() {
    }

    public static Font monospaced() {
        Font base = UIManager.getFont("TextArea.font");
        int size = base == null ? 13 : base.getSize();
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    public static String format(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant);
    }

    /** The first UUID anywhere in {@code text}, or {@code null}. */
    public static UUID findUuid(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = UUID_PATTERN.matcher(text);
        return matcher.find() ? UUID.fromString(matcher.group()) : null;
    }

    public static String nullToEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static void copy(String text) {
        if (text != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    public static void error(Component parent, String title, String message) {
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(UIManager.getFont("Label.font"));
        JComponent content = area;
        if (message != null && (message.length() > 400 || message.lines().count() > 8)) {
            JScrollPane scroll = new JScrollPane(area);
            scroll.setPreferredSize(new Dimension(620, 280));
            content = scroll;
        } else {
            area.setColumns(50);
            area.setSize(new Dimension(520, Short.MAX_VALUE));
        }
        JOptionPane.showMessageDialog(parent, content, title, JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String title, String message) {
        return JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /**
     * Runs {@code task} off the event thread with a wait cursor on {@code owner};
     * {@code onSuccess} runs on the event thread, a failure opens an error dialog
     * titled "{@code what} failed".
     */
    public static <T> void async(Component owner, String what, Callable<T> task, Consumer<T> onSuccess) {
        Cursor previous = owner.getCursor();
        owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                owner.setCursor(previous);
                try {
                    onSuccess.accept(get());
                } catch (ExecutionException e) {
                    error(owner, what + " failed", SenderService.describe(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    public static Color statusColor(RecordStatus status) {
        boolean dark = isDark();
        return switch (status) {
            case ACCEPTED -> dark ? new Color(0x2E7D32) : new Color(0xC8E6C9);
            case REJECTED -> dark ? new Color(0xB71C1C) : new Color(0xFFCDD2);
            case SEND_FAILED, DELETED -> dark ? new Color(0x5A5A5A) : new Color(0xE0E0E0);
            case UNKNOWN -> dark ? new Color(0x455A64) : new Color(0xECEFF1);
            default -> dark ? new Color(0x8D6E00) : new Color(0xFFECB3);
        };
    }

    public static boolean isDark() {
        Color background = UIManager.getColor("Panel.background");
        return background != null && (background.getRed() + background.getGreen() + background.getBlue()) / 3 < 128;
    }

    /** The highlight for a row whose status just changed. */
    public static Color flashColor() {
        return isDark() ? new Color(0x1565C0) : new Color(0xBBDEFB);
    }

    /** A generated window/tray icon - an envelope-ish blue square, no image files needed. */
    public static BufferedImage appIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1565C0));
        g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        g.setColor(Color.WHITE);
        int m = size / 5;
        g.setStroke(new java.awt.BasicStroke(Math.max(1f, size / 16f)));
        g.drawRect(m, m + size / 10, size - 2 * m, size - 2 * m - size / 5);
        g.drawLine(m, m + size / 10, size / 2, size / 2 + size / 20);
        g.drawLine(size - m, m + size / 10, size / 2, size / 2 + size / 20);
        g.dispose();
        return image;
    }
}
