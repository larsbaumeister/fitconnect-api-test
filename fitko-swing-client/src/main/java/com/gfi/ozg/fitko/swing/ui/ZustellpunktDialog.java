package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.DestinationInfo;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.UUID;

/** Add/edit form for one Zustellpunkt, with "Fetch info" to read its public data. */
public class ZustellpunktDialog extends JDialog {

    private final AppContext ctx;
    private final Zustellpunkt working;
    private final JTextField label = new JTextField(30);
    private final JTextField destinationId = new JTextField(30);
    private final JComboBox<Connection> connection = new JComboBox<>();
    private final JTextField tags = new JTextField(30);
    private final JTextArea notes = new JTextArea(3, 30);
    private final JTextArea info = new JTextArea(9, 30);
    private Zustellpunkt result;

    public ZustellpunktDialog(Window owner, AppContext ctx, Zustellpunkt original, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.working = original.copy();

        ctx.data.connections().forEach(connection::addItem);
        connection.setSelectedItem(ctx.connectionFor(working, null));
        label.setText(working.label);
        destinationId.setText(working.destinationId == null ? "" : working.destinationId.toString());
        tags.setText(Ui.nullToEmpty(working.tags));
        tags.setToolTipText("Free text, e.g. 'xml team-a' - searchable");
        notes.setText(Ui.nullToEmpty(working.notes));
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        info.setEditable(false);
        info.setFont(Ui.monospaced());
        showInfo();

        JButton fetch = new JButton("Fetch info");
        fetch.setToolTipText("Read the destination's services, schemas and regions from the submission API");
        fetch.addActionListener(e -> fetchInfo());

        Form form = new Form()
                .row("Label", label)
                .row("Destination ID", destinationId, fetch)
                .row("Connection", connection)
                .row("Tags", tags)
                .row("Notes", new JScrollPane(notes))
                .full(new JScrollPane(info), true);
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(640, 520));
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; returns the saved Zustellpunkt or {@code null} if cancelled. */
    public Zustellpunkt showDialog() {
        setVisible(true);
        return result;
    }

    private UUID parseDestination() {
        UUID id = Ui.findUuid(destinationId.getText());
        if (id == null) {
            Ui.error(this, "Zustellpunkt", "Please enter the destination ID (a UUID).");
        }
        return id;
    }

    private void fetchInfo() {
        UUID id = parseDestination();
        Connection conn = (Connection) connection.getSelectedItem();
        if (id == null) {
            return;
        }
        if (conn == null) {
            Ui.error(this, "Zustellpunkt", "Add a connection first (File → Connections).");
            return;
        }
        Ui.async(this, "Fetch info", () -> ctx.destinations.fetch(conn, id), fetched -> {
            working.info = fetched;
            if (label.getText().isBlank()) {
                label.setText(defaultLabel(id, fetched));
            }
            showInfo();
        });
    }

    static String defaultLabel(UUID id, DestinationInfo info) {
        String shortId = id.toString().substring(0, 8);
        if (info != null && !info.services.isEmpty() && info.services.get(0).identifier != null) {
            String identifier = info.services.get(0).identifier;
            return identifier.substring(identifier.lastIndexOf(':') + 1) + " · " + shortId;
        }
        return "Zustellpunkt " + shortId;
    }

    private void showInfo() {
        info.setText(ZustellpunktePanel.describe(working.info));
        info.setCaretPosition(0);
    }

    private void save() {
        UUID id = parseDestination();
        if (id == null) {
            return;
        }
        working.destinationId = id;
        working.label = label.getText().isBlank() ? defaultLabel(id, working.info) : label.getText().trim();
        Connection conn = (Connection) connection.getSelectedItem();
        working.connectionId = conn == null ? null : conn.id;
        working.tags = Ui.blankToNull(tags.getText());
        working.notes = Ui.blankToNull(notes.getText());
        result = working;
        dispose();
    }
}
