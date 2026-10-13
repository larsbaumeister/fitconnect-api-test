package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;

/**
 * Manages sender connections: named client-id/secret sets per environment.
 * Edits apply to the selected connection on "Save".
 */
public class ConnectionsDialog extends JDialog {

    private final AppContext ctx;
    private final DefaultListModel<Connection> listModel = new DefaultListModel<>();
    private final JList<Connection> list = new JList<>(listModel);

    private final JTextField label = new JTextField(24);
    private final JComboBox<String> environment = new JComboBox<>(new String[]{"TEST", "STAGE", "PROD"});
    private final JTextField clientId = new JTextField(24);
    private final JPasswordField clientSecret = new JPasswordField(24);
    private final JCheckBox showSecret = new JCheckBox("Show");
    private final JCheckBox fromEnv = new JCheckBox("Read client id / secret from environment variables");
    private final JTextField clientIdEnv = new JTextField(24);
    private final JTextField clientSecretEnv = new JTextField(24);
    private final JTextField fromDestination = new JTextField(24);
    private final JTextField authBaseUrl = new JTextField(24);
    private final JTextField submissionBaseUrl = new JTextField(24);
    private final JLabel envStatus = new JLabel(" ");
    private final JButton save = new JButton("Save");
    private final JButton test = new JButton("Test connection");
    private final char echoChar;

    private Connection editing;

    public ConnectionsDialog(Window owner, AppContext ctx) {
        super(owner, "Connections", ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.echoChar = clientSecret.getEchoChar();

        environment.setEditable(true);
        environment.setToolTipText("TEST, STAGE, PROD - or a custom name, which then needs both base URLs");
        fromDestination.setToolTipText("<html>The destination ID this client sends from (sent as <code>fromDestinationId</code>)."
                + "<br>One of the destinations assigned to the client in the Self-Service Portal.</html>");
        authBaseUrl.setToolTipText("Optional - overrides the environment's built-in auth URL (with or without /token)");
        submissionBaseUrl.setToolTipText("Optional - overrides the environment's built-in submission API URL");

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                applyEdits();
                load(list.getSelectedValue());
            }
        });
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel listButtons = new JPanel(new GridLayout(1, 3, 4, 4));
        JButton add = new JButton("New");
        JButton duplicate = new JButton("Duplicate");
        JButton delete = new JButton("Delete");
        add.addActionListener(e -> addConnection(new Connection()));
        duplicate.addActionListener(e -> duplicate());
        delete.addActionListener(e -> delete());
        listButtons.add(add);
        listButtons.add(duplicate);
        listButtons.add(delete);
        left.add(listButtons, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(240, 300));

        showSecret.addActionListener(e -> clientSecret.setEchoChar(showSecret.isSelected() ? (char) 0 : echoChar));
        fromEnv.addActionListener(e -> updateEnabled());

        Form form = new Form()
                .row("Label", label)
                .row("Environment", environment)
                .row("Client id", clientId)
                .row("Client secret", clientSecret, showSecret)
                .full(fromEnv, false)
                .row("Client id variable", clientIdEnv)
                .row("Client secret variable", clientSecretEnv)
                .full(envStatus, false)
                .row("From destination", fromDestination)
                .full(sectionLabel("Advanced: base URL overrides (leave empty for the SDK defaults)"), false)
                .row("Auth base URL", authBaseUrl)
                .row("Submission API URL", submissionBaseUrl)
                .filler();
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel formButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        save.addActionListener(e -> saveCurrent());
        test.addActionListener(e -> testCurrent());
        formButtons.add(test);
        formButtons.add(save);
        JPanel right = new JPanel(new BorderLayout());
        right.add(form, BorderLayout.CENTER);
        right.add(formButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            applyEdits();
            dispose();
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                applyEdits();
            }
        });
        bottom.add(close);

        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        ctx.data.connections().forEach(listModel::addElement);
        if (!listModel.isEmpty()) {
            list.setSelectedIndex(0);
        } else {
            load(null);
        }
        pack();
        setMinimumSize(new Dimension(820, 480));
        setLocationRelativeTo(owner);
    }

    /** Opens the dialog with a fresh connection ready to fill in. */
    public ConnectionsDialog startNew() {
        Connection c = new Connection();
        c.label = "My TEST sender";
        addConnection(c);
        return this;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return label;
    }

    private void addConnection(Connection connection) {
        if (connection.label == null || connection.label.isBlank()) {
            connection.label = "New connection";
        }
        ctx.data.connections().add(connection);
        ctx.data.saveConnections();
        listModel.addElement(connection);
        list.setSelectedValue(connection, true);
        label.requestFocusInWindow();
        label.selectAll();
    }

    private void duplicate() {
        Connection selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        Connection copy = selected.copy();
        copy.id = java.util.UUID.randomUUID().toString();
        copy.label = selected.label + " (copy)";
        addConnection(copy);
    }

    private void delete() {
        Connection selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        List<Zustellpunkt> users = ctx.data.zustellpunkte().stream()
                .filter(z -> selected.id.equals(z.connectionId)).toList();
        String message = "Delete connection '" + selected.label + "'?";
        if (!users.isEmpty()) {
            message += "\n\n" + users.size() + " Zustellpunkt(e) use it and will fall back to the default connection."
                    + "\nThe status of submissions sent with it can no longer be checked.";
        }
        if (!Ui.confirm(this, "Delete connection", message)) {
            return;
        }
        ctx.data.connections().remove(selected);
        users.forEach(z -> z.connectionId = null);
        ctx.data.saveConnections();
        if (!users.isEmpty()) {
            ctx.data.saveZustellpunkte();
        }
        ctx.sender.clients().invalidate(selected.id);
        listModel.removeElement(selected);
        if (!listModel.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void load(Connection connection) {
        editing = connection;
        boolean present = connection != null;
        for (javax.swing.JComponent c : List.of(label, environment, clientId, clientSecret, showSecret, fromEnv,
                clientIdEnv, clientSecretEnv, fromDestination, authBaseUrl, submissionBaseUrl, save, test)) {
            c.setEnabled(present);
        }
        if (!present) {
            return;
        }
        label.setText(connection.label);
        environment.setSelectedItem(connection.environment);
        clientId.setText(Ui.nullToEmpty(connection.clientId));
        clientSecret.setText(Ui.nullToEmpty(connection.clientSecret));
        fromEnv.setSelected(connection.credentialsFromEnv);
        clientIdEnv.setText(connection.clientIdEnvVar);
        clientSecretEnv.setText(connection.clientSecretEnvVar);
        fromDestination.setText(connection.fromDestinationId == null ? "" : connection.fromDestinationId.toString());
        authBaseUrl.setText(Ui.nullToEmpty(connection.authBaseUrl));
        submissionBaseUrl.setText(Ui.nullToEmpty(connection.submissionBaseUrl));
        updateEnabled();
    }

    private void updateEnabled() {
        boolean env = fromEnv.isSelected();
        clientId.setEnabled(!env);
        clientSecret.setEnabled(!env);
        showSecret.setEnabled(!env);
        clientIdEnv.setEnabled(env);
        clientSecretEnv.setEnabled(env);
        if (env) {
            boolean idSet = System.getenv(clientIdEnv.getText().trim()) != null;
            boolean secretSet = System.getenv(clientSecretEnv.getText().trim()) != null;
            envStatus.setText(idSet && secretSet ? "Both variables are set in this process."
                    : "Not set in this process: " + (idSet ? "" : clientIdEnv.getText() + " ")
                    + (secretSet ? "" : clientSecretEnv.getText()));
        } else {
            envStatus.setText(" ");
        }
    }

    private void writeForm(Connection target) {
        target.label = label.getText().trim();
        Object env = environment.getEditor().getItem();
        target.environment = env == null || env.toString().isBlank() ? "TEST" : env.toString().trim().toUpperCase();
        target.clientId = Ui.blankToNull(clientId.getText());
        target.clientSecret = Ui.blankToNull(new String(clientSecret.getPassword()));
        target.credentialsFromEnv = fromEnv.isSelected();
        target.clientIdEnvVar = clientIdEnv.getText().trim();
        target.clientSecretEnvVar = clientSecretEnv.getText().trim();
        target.fromDestinationId = Ui.findUuid(fromDestination.getText());
        target.authBaseUrl = Ui.blankToNull(authBaseUrl.getText());
        target.submissionBaseUrl = Ui.blankToNull(submissionBaseUrl.getText());
    }

    /** Keeps unsaved edits when switching to another connection or closing - nobody expects to lose them. */
    private void applyEdits() {
        if (editing == null || !ctx.data.connections().contains(editing)) {
            return;
        }
        Connection before = editing.copy();
        writeForm(editing);
        if (editing.label.isBlank()) {
            editing.label = before.label;
        }
        ctx.data.saveConnections();
        ctx.sender.clients().invalidate(editing.id);
        list.repaint();
    }

    private void saveCurrent() {
        if (editing == null) {
            return;
        }
        if (label.getText().isBlank()) {
            Ui.error(this, "Connection", "Please enter a label.");
            return;
        }
        if (!fromDestination.getText().isBlank() && Ui.findUuid(fromDestination.getText()) == null) {
            Ui.error(this, "Connection", "'From destination' must be a destination ID (a UUID).");
            return;
        }
        writeForm(editing);
        ctx.data.saveConnections();
        ctx.sender.clients().invalidate(editing.id);
        list.repaint();
        updateEnabled();
        ctx.navigator.message("Connection '" + editing.label + "' saved");
    }

    private void testCurrent() {
        if (editing == null) {
            return;
        }
        Connection candidate = editing.copy();
        writeForm(candidate);
        Ui.async(this, "Test connection", () -> ctx.destinations.fetchToken(candidate), token ->
                Ui.info(this, "Test connection", "OK - the auth server issued a token for '" + candidate.label
                        + "' (" + candidate.environment + ")."));
    }
}
