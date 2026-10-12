package com.gfi.ozg.fitko.swing.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** Bottom console showing what the app and the SDK log (requests, errors). */
public class LogPanel extends JPanel {

    private static final int MAX_CHARS = 400_000;

    private final JTextArea text = new JTextArea();
    private final JCheckBox follow = new JCheckBox("Follow", true);

    public LogPanel() {
        super(new BorderLayout());
        text.setEditable(false);
        text.setFont(Ui.monospaced());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> text.setText(""));
        bar.add(follow);
        bar.add(clear);
        add(bar, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        for (String line : LogPanelAppender.attach(line -> SwingUtilities.invokeLater(() -> append(line)))) {
            append(line);
        }
    }

    private void append(String line) {
        text.append(line);
        text.append("\n");
        int excess = text.getDocument().getLength() - MAX_CHARS;
        if (excess > 0) {
            try {
                text.getDocument().remove(0, excess);
            } catch (BadLocationException ignored) {
                // cannot happen: excess is within the document
            }
        }
        if (follow.isSelected()) {
            text.setCaretPosition(text.getDocument().getLength());
        }
    }
}
