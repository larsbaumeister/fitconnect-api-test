package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.DataFormat;
import com.gfi.ozg.fitko.swing.model.DestinationInfo;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;
import com.gfi.ozg.fitko.swing.service.PayloadTemplate;
import com.gfi.ozg.fitko.swing.service.PayloadTools;
import com.gfi.ozg.fitko.swing.service.SendRequest;
import com.gfi.ozg.fitko.swing.service.SubmissionFactory;
import com.gfi.ozg.fitko.swing.store.AppData;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * The "New submission" tab: preset bar, the submission form, the payload
 * editor, and Send.
 */
public class ComposePanel extends JPanel {

    /** Combo entry for "form not bound to a saved preset". */
    private static final Preset UNSAVED = new Preset();

    static {
        UNSAVED.name = "— unsaved —";
    }

    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            "pdf", "application/pdf", "xml", "application/xml", "json", "application/json",
            "txt", "text/plain", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "csv", "text/csv", "zip", "application/zip", "html", "text/html");

    private final AppContext ctx;

    private final DefaultComboBoxModel<Preset> presetModel = new DefaultComboBoxModel<>();
    private final JComboBox<Preset> presetCombo = new JComboBox<>(presetModel);

    private final JComboBox<Object> zustellpunktCombo = new JComboBox<>();
    private final JButton saveAsZustellpunkt = new JButton("Save…");
    private final JComboBox<Connection> connectionCombo = new JComboBox<>();
    private final JLabel environmentBadge = new JLabel();
    private final JComboBox<String> serviceIdCombo = editableCombo();
    private final JTextField serviceName = new JTextField();
    private final JComboBox<String> regionCombo = editableCombo();
    private final JRadioButton xml = new JRadioButton("XML", true);
    private final JRadioButton json = new JRadioButton("JSON");
    private final JComboBox<String> schemaCombo = editableCombo();
    private final JComboBox<String> metadataVersionCombo = editableCombo();
    private final JTextField replyEmail = new JTextField();
    private final JLabel replyHint = hintLabel();
    private final JTextField caseId = new JTextField();
    private final DefaultListModel<AttachmentSpec> attachmentModel = new DefaultListModel<>();
    private final JList<AttachmentSpec> attachmentList = new JList<>(attachmentModel);
    private final JLabel destinationHint = hintLabel();

    private final JTextArea payload = new JTextArea();
    private final JSpinner copies = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JButton sendButton = new JButton("Send");
    private final JLabel sendResult = new JLabel(" ");
    private final JSplitPane split;

    /** Suppresses cascading listener updates while the form is filled programmatically. */
    private boolean loading;
    private String currentPresetId;

    public ComposePanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;

        // --- preset bar ---
        JToolBar presetBar = new JToolBar();
        presetBar.setFloatable(false);
        presetBar.add(new JLabel("Preset "));
        presetCombo.setPreferredSize(new Dimension(320, presetCombo.getPreferredSize().height));
        presetCombo.setMaximumSize(new Dimension(420, presetCombo.getPreferredSize().height));
        presetCombo.addActionListener(e -> {
            if (!loading) {
                Preset selected = (Preset) presetCombo.getSelectedItem();
                if (selected != null && selected != UNSAVED) {
                    loadPreset(selected);
                }
            }
        });
        presetBar.add(presetCombo);
        presetBar.addSeparator();
        presetBar.add(button("New", "Start with an empty form", this::newForm));
        presetBar.add(button("Save", "Save the form into the selected preset", this::savePreset));
        presetBar.add(button("Save as…", "Save the form as a new preset", this::savePresetAs));
        presetBar.add(button("Rename…", null, this::renamePreset));
        presetBar.add(button("Delete", null, this::deletePreset));

        // --- form ---
        zustellpunktCombo.setEditable(true);
        zustellpunktCombo.setToolTipText("Pick a saved Zustellpunkt or paste any destination ID");
        zustellpunktCombo.addActionListener(e -> {
            if (!loading) {
                onZustellpunktChanged();
            }
        });
        saveAsZustellpunkt.setToolTipText("Save this destination ID as a Zustellpunkt");
        saveAsZustellpunkt.addActionListener(e -> saveAdHocZustellpunkt());
        JButton check = button("Check", "Check that the destination exists and has an encryption key", this::checkDestination);
        JButton fetch = button("Fetch info", "Read the destination's services, schemas and regions", this::fetchInfo);
        JPanel zustellpunktButtons = flow(saveAsZustellpunkt, check, fetch);

        connectionCombo.addActionListener(e -> updateEnvironmentBadge());
        environmentBadge.setOpaque(true);
        environmentBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        environmentBadge.setFont(environmentBadge.getFont().deriveFont(Font.BOLD));

        serviceIdCombo.addActionListener(e -> {
            if (!loading) {
                onServiceChanged();
            }
        });
        schemaCombo.addActionListener(e -> {
            if (!loading) {
                onSchemaChanged();
            }
        });
        ButtonGroup formatGroup = new ButtonGroup();
        formatGroup.add(xml);
        formatGroup.add(json);
        replyEmail.getDocument().addDocumentListener(SimpleDocumentListener.of(this::updateHints));
        caseId.setToolTipText("Leave empty to start a new case; set to append this submission to an existing case");
        metadataVersionCombo.setToolTipText("Leave empty to negotiate with the destination");

        attachmentList.setVisibleRowCount(3);
        attachmentList.setTransferHandler(new FileDropHandler());
        JPanel attachmentButtons = new JPanel(new GridLayout(3, 1, 2, 2));
        attachmentButtons.add(button("Add…", "Add files (or drop them on the list)", this::addAttachments));
        attachmentButtons.add(button("MIME…", "Change the MIME type", this::editAttachmentMime));
        attachmentButtons.add(button("Remove", null, this::removeAttachments));
        JPanel attachments = new JPanel(new BorderLayout(4, 0));
        attachments.add(new JScrollPane(attachmentList), BorderLayout.CENTER);
        attachments.add(attachmentButtons, BorderLayout.EAST);

        JButton newCase = button("New case", "Clear the case id", () -> caseId.setText(""));

        Form form = new Form()
                .row("Zustellpunkt", zustellpunktCombo, zustellpunktButtons)
                .full(destinationHint, false)
                .row("Connection", connectionCombo, environmentBadge)
                .row("Service id", serviceIdCombo)
                .row("Service name", serviceName)
                .row("Region", regionCombo)
                .row("Format", flow(xml, json))
                .row("Data schema", schemaCombo)
                .row("Metadata version", metadataVersionCombo)
                .row("Reply e-mail", replyEmail)
                .full(replyHint, false)
                .row("Case id", caseId, newCase)
                .row("Attachments", attachments)
                .filler();
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // --- payload editor ---
        payload.setFont(Ui.monospaced());
        payload.setTabSize(2);
        JToolBar payloadBar = new JToolBar();
        payloadBar.setFloatable(false);
        payloadBar.add(new JLabel("Payload "));
        payloadBar.add(button("Load file…", null, this::loadPayloadFile));
        payloadBar.add(button("Pretty print", null, this::prettyPrint));
        payloadBar.add(button("Check", "Check that the payload is well-formed", this::checkPayload));
        JButton insert = new JButton("Insert ▾");
        insert.setToolTipText("Insert a placeholder - resolved freshly on every send");
        insert.addActionListener(e -> placeholderMenu().show(insert, 0, insert.getHeight()));
        payloadBar.add(insert);
        payloadBar.add(button("Preview", "Show the payload with placeholders resolved", this::previewPayload));
        payloadBar.add(button("Example", "Replace the payload with a small example for the selected format", this::examplePayload));
        JPanel payloadPanel = new JPanel(new BorderLayout());
        payloadPanel.add(payloadBar, BorderLayout.NORTH);
        payloadPanel.add(new JScrollPane(payload), BorderLayout.CENTER);
        JLabel placeholderHint = hintLabel();
        placeholderHint.setText("Placeholders: ${uuid} ${now} ${date} ${counter} ${caseId}");
        payloadPanel.add(placeholderHint, BorderLayout.SOUTH);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), payloadPanel);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        if (ctx.data.settings().composeSplit > 0) {
            split.setDividerLocation(ctx.data.settings().composeSplit);
        }

        // --- send bar ---
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        sendButton.addActionListener(e -> send());
        copies.setToolTipText("Send this many submissions; placeholders are resolved for each one");
        JPanel sendBar = new JPanel(new BorderLayout());
        sendBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        sendBar.add(sendResult, BorderLayout.CENTER);
        sendBar.add(flow(new JLabel("Copies"), copies, sendButton), BorderLayout.EAST);

        add(presetBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(sendBar, BorderLayout.SOUTH);

        reloadPresets();
        reloadZustellpunkte();
        reloadConnections();
        metadataVersionOptions();
        Preset last = ctx.data.preset(ctx.data.settings().lastPresetId).orElse(null);
        if (last != null) {
            loadPreset(last);
        } else {
            newForm();
        }

        ctx.data.addListener(kind -> {
            switch (kind) {
                case PRESETS -> reloadPresets();
                case ZUSTELLPUNKTE -> {
                    reloadZustellpunkte();
                    onZustellpunktInfoMaybeChanged();
                }
                case CONNECTIONS -> reloadConnections();
                default -> { }
            }
        });
    }

    public int dividerLocation() {
        return split.getDividerLocation();
    }

    // --- small builders ---------------------------------------------------

    private static JComboBox<String> editableCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        return combo;
    }

    private static JLabel hintLabel() {
        JLabel label = new JLabel(" ");
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }

    private static JButton button(String text, String tooltip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JPanel flow(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JComponent c : components) {
            panel.add(c);
        }
        return panel;
    }

    private static String text(JComboBox<?> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    /** Replaces a combo's options and keeps (or sets) its text. */
    private void setOptions(JComboBox<String> combo, List<String> options, String text) {
        boolean wasLoading = loading;
        loading = true;
        try {
            combo.removeAllItems();
            options.forEach(combo::addItem);
            combo.setSelectedItem(text == null ? "" : text);
        } finally {
            loading = wasLoading;
        }
    }

    // --- reloads when the data changes elsewhere --------------------------

    private void reloadPresets() {
        boolean wasLoading = loading;
        loading = true;
        try {
            presetModel.removeAllElements();
            presetModel.addElement(UNSAVED);
            ctx.data.presets().stream()
                    .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                    .forEach(presetModel::addElement);
            Preset current = ctx.data.preset(currentPresetId).orElse(null);
            if (current == null) {
                currentPresetId = null;
            }
            presetCombo.setSelectedItem(current == null ? UNSAVED : current);
        } finally {
            loading = wasLoading;
        }
    }

    private void reloadZustellpunkte() {
        boolean wasLoading = loading;
        loading = true;
        try {
            Object current = zustellpunktCombo.getEditor().getItem();
            Zustellpunkt selected = selectedZustellpunkt();
            zustellpunktCombo.removeAllItems();
            ctx.data.zustellpunkte().stream()
                    .sorted((a, b) -> a.label.compareToIgnoreCase(b.label))
                    .forEach(zustellpunktCombo::addItem);
            Zustellpunkt refreshed = selected == null ? null : ctx.data.zustellpunkt(selected.id).orElse(null);
            if (refreshed != null) {
                zustellpunktCombo.setSelectedItem(refreshed);
            } else if (selected != null) {
                // Deleted meanwhile: keep sending to the same destination, now as an ad-hoc id.
                zustellpunktCombo.setSelectedItem(selected.destinationId == null ? "" : selected.destinationId.toString());
            } else {
                zustellpunktCombo.setSelectedItem(current);
            }
        } finally {
            loading = wasLoading;
        }
        updateHints();
    }

    private void reloadConnections() {
        boolean wasLoading = loading;
        loading = true;
        try {
            Connection current = (Connection) connectionCombo.getSelectedItem();
            connectionCombo.removeAllItems();
            ctx.data.connections().forEach(connectionCombo::addItem);
            Connection refreshed = current == null ? null : ctx.data.connection(current.id).orElse(null);
            connectionCombo.setSelectedItem(refreshed != null ? refreshed : ctx.connectionFor(selectedZustellpunkt(), null));
        } finally {
            loading = wasLoading;
        }
        updateEnvironmentBadge();
    }

    private void metadataVersionOptions() {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(SubmissionFactory.supportedMetadataVersions());
        setOptions(metadataVersionCombo, options, text(metadataVersionCombo));
    }

    // --- Zustellpunkt / destination info ----------------------------------

    /** The saved Zustellpunkt the combo points at, or {@code null} for an ad-hoc destination id. */
    private Zustellpunkt selectedZustellpunkt() {
        Object item = zustellpunktCombo.getEditor().getItem();
        if (item instanceof Zustellpunkt z) {
            return z;
        }
        String text = item == null ? "" : item.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        for (Zustellpunkt z : ctx.data.zustellpunkte()) {
            if (z.toString().equals(text)) {
                return z;
            }
        }
        UUID id = Ui.findUuid(text);
        return id == null ? null : ctx.data.zustellpunkte().stream()
                .filter(z -> id.equals(z.destinationId)).findFirst().orElse(null);
    }

    private UUID selectedDestinationId() {
        Zustellpunkt z = selectedZustellpunkt();
        if (z != null) {
            return z.destinationId;
        }
        Object item = zustellpunktCombo.getEditor().getItem();
        return Ui.findUuid(item == null ? null : item.toString());
    }

    private void onZustellpunktChanged() {
        Zustellpunkt z = selectedZustellpunkt();
        Connection connection = ctx.connectionFor(z, null);
        if (connection != null) {
            connectionCombo.setSelectedItem(connection);
        }
        onZustellpunktInfoMaybeChanged();
    }

    /** Refreshes the service/schema/region options from the selected Zustellpunkt's fetched info. */
    private void onZustellpunktInfoMaybeChanged() {
        DestinationInfo info = currentInfo();
        List<String> services = info == null ? List.of()
                : info.services.stream().map(s -> s.identifier).filter(Objects::nonNull).toList();
        setOptions(serviceIdCombo, services, text(serviceIdCombo).isEmpty() && !services.isEmpty() ? services.get(0) : text(serviceIdCombo));
        onServiceChanged();
        if (info != null && !info.metadataVersions.isEmpty()) {
            metadataVersionCombo.setToolTipText("Destination accepts: " + String.join(", ", info.metadataVersions)
                    + ". Leave empty to negotiate.");
        } else {
            metadataVersionCombo.setToolTipText("Leave empty to negotiate with the destination");
        }
        updateHints();
    }

    private DestinationInfo currentInfo() {
        Zustellpunkt z = selectedZustellpunkt();
        return z == null ? null : z.info;
    }

    private void onServiceChanged() {
        DestinationInfo info = currentInfo();
        DestinationInfo.ServiceInfo service = info == null ? null : info.service(text(serviceIdCombo));
        List<String> regions = service == null ? List.of() : service.regions;
        List<String> schemas = service == null ? List.of() : service.schemas.stream().map(s -> s.schemaUri).toList();
        List<String> regionOptions = new ArrayList<>();
        regionOptions.add("");
        regionOptions.addAll(regions);
        setOptions(regionCombo, regionOptions, text(regionCombo));
        String schema = text(schemaCombo);
        // Pick the destination's schema when none is set; replace a foreign one only on user action, not while loading a preset.
        if (!schemas.isEmpty() && (schema.isEmpty() || (!loading && !schemas.contains(schema)))) {
            schema = schemas.get(0);
        }
        setOptions(schemaCombo, schemas, schema);
        if (!loading) {
            onSchemaChanged();
        }
    }

    private void onSchemaChanged() {
        DestinationInfo info = currentInfo();
        DestinationInfo.ServiceInfo service = info == null ? null : info.service(text(serviceIdCombo));
        if (service == null) {
            return;
        }
        String schemaUri = text(schemaCombo);
        service.schemas.stream().filter(s -> schemaUri.equals(s.schemaUri)).findFirst().ifPresent(s -> {
            if (s.dataFormat() == DataFormat.JSON) {
                json.setSelected(true);
            } else {
                xml.setSelected(true);
            }
        });
    }

    private void updateHints() {
        Zustellpunkt z = selectedZustellpunkt();
        UUID id = selectedDestinationId();
        saveAsZustellpunkt.setEnabled(z == null && id != null);
        if (z != null) {
            destinationHint.setText(z.info == null
                    ? "No destination info yet - \"Fetch info\" fills the service, schema and region lists."
                    : z.info.services.size() + " service(s) · status " + Ui.nullToEmpty(z.info.status)
                    + " · info from " + Ui.format(z.info.fetchedAt));
        } else if (id != null) {
            destinationHint.setText("Ad-hoc destination " + id + " - not saved as a Zustellpunkt");
        } else {
            destinationHint.setText("Pick a Zustellpunkt or paste a destination ID.");
        }
        DestinationInfo info = z == null ? null : z.info;
        if (info != null && !replyEmail.getText().isBlank() && !info.allowsReplyChannel("eMail")) {
            replyHint.setText("⚠ This destination doesn't list e-mail as a reply channel - it will likely reject the submission.");
        } else {
            replyHint.setText(" ");
        }
    }

    private void updateEnvironmentBadge() {
        Connection connection = (Connection) connectionCombo.getSelectedItem();
        String env = connection == null ? "—" : connection.environment;
        environmentBadge.setText(env);
        Color color = switch (env.toUpperCase(Locale.ROOT)) {
            case "TEST" -> new Color(0x2E7D32);
            case "STAGE" -> new Color(0xEF6C00);
            case "PROD" -> new Color(0xC62828);
            default -> new Color(0x546E7A);
        };
        environmentBadge.setBackground(color);
        environmentBadge.setForeground(Color.WHITE);
        environmentBadge.setToolTipText(connection == null ? "No connection" : "Sends with " + connection);
    }

    private void saveAdHocZustellpunkt() {
        UUID id = selectedDestinationId();
        if (id == null) {
            return;
        }
        Zustellpunkt template = new Zustellpunkt();
        template.destinationId = id;
        Connection connection = (Connection) connectionCombo.getSelectedItem();
        template.connectionId = connection == null ? null : connection.id;
        Zustellpunkt saved = new ZustellpunktDialog(SwingUtilities.getWindowAncestor(this), ctx, template, "Save as Zustellpunkt").showDialog();
        if (saved != null) {
            ctx.data.upsert(saved);
            zustellpunktCombo.setSelectedItem(saved);
        }
    }

    private Connection requireConnection() {
        Connection connection = (Connection) connectionCombo.getSelectedItem();
        if (connection == null) {
            Ui.error(this, "No connection", "Add a sender connection first (File → Connections…).");
        }
        return connection;
    }

    private void checkDestination() {
        UUID id = selectedDestinationId();
        Connection connection = requireConnection();
        if (id == null) {
            Ui.error(this, "Check", "Pick a Zustellpunkt or paste a destination ID first.");
            return;
        }
        if (connection == null) {
            return;
        }
        Ui.async(this, "Check destination", () -> ctx.sender.checkDestination(connection, id), key ->
                Ui.info(this, "Check destination", "OK - destination " + id + " exists and published an encryption key."));
    }

    private void fetchInfo() {
        UUID id = selectedDestinationId();
        Connection connection = requireConnection();
        if (id == null) {
            Ui.error(this, "Fetch info", "Pick a Zustellpunkt or paste a destination ID first.");
            return;
        }
        if (connection == null) {
            return;
        }
        Ui.async(this, "Fetch info", () -> ctx.destinations.fetch(connection, id), info -> {
            Zustellpunkt z = selectedZustellpunkt();
            if (z == null) {
                // Ad-hoc destination: offer to save it, otherwise the info would be lost.
                Zustellpunkt template = new Zustellpunkt();
                template.destinationId = id;
                template.connectionId = connection.id;
                template.info = info;
                template.label = ZustellpunktDialog.defaultLabel(id, info);
                if (JOptionPane.showConfirmDialog(this, "Found " + info.services.size()
                                + " service(s). Save this destination as a Zustellpunkt so the lists can be filled?",
                        "Fetch info", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    ctx.data.upsert(template);
                    zustellpunktCombo.setSelectedItem(template);
                } else {
                    Ui.info(this, "Fetch info", ZustellpunktePanel.describe(info));
                }
                return;
            }
            z.info = info;
            ctx.data.saveZustellpunkte();
            ctx.navigator.message("Fetched info for '" + z.label + "': " + info.services.size() + " service(s)");
        });
    }

    // --- presets ----------------------------------------------------------

    private void newForm() {
        currentPresetId = null;
        Preset blank = new Preset();
        blank.name = "";
        writeForm(blank);
        reloadPresets();
    }

    private void loadPreset(Preset preset) {
        currentPresetId = preset.id;
        ctx.data.settings().lastPresetId = preset.id;
        ctx.data.saveSettings();
        writeForm(preset);
        reloadPresets();
    }

    private void savePreset() {
        Preset current = ctx.data.preset(currentPresetId).orElse(null);
        if (current == null) {
            savePresetAs();
            return;
        }
        Preset updated = readForm();
        updated.id = current.id;
        updated.name = current.name;
        ctx.data.upsert(updated);
        ctx.navigator.message("Preset '" + updated.name + "' saved");
    }

    private void savePresetAs() {
        Preset current = ctx.data.preset(currentPresetId).orElse(null);
        String name = JOptionPane.showInputDialog(this, "Preset name", current == null ? "" : current.name + " (copy)");
        if (name == null || name.isBlank()) {
            return;
        }
        Preset preset = readForm();
        preset.id = UUID.randomUUID().toString();
        preset.name = name.trim();
        currentPresetId = preset.id;
        ctx.data.settings().lastPresetId = preset.id;
        ctx.data.saveSettings();
        ctx.data.upsert(preset);
        ctx.navigator.message("Preset '" + preset.name + "' saved");
    }

    private void renamePreset() {
        Preset current = ctx.data.preset(currentPresetId).orElse(null);
        if (current == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New name", current.name);
        if (name != null && !name.isBlank()) {
            current.name = name.trim();
            ctx.data.savePresets();
        }
    }

    private void deletePreset() {
        Preset current = ctx.data.preset(currentPresetId).orElse(null);
        if (current == null || !Ui.confirm(this, "Delete preset", "Delete preset '" + current.name + "'?")) {
            return;
        }
        ctx.data.presets().remove(current);
        currentPresetId = null;
        ctx.data.savePresets();
    }

    /** The form's current state as a preset (id/name of the bound preset, if any). */
    Preset readForm() {
        Preset p = new Preset();
        Preset bound = ctx.data.preset(currentPresetId).orElse(null);
        if (bound != null) {
            p.id = bound.id;
            p.name = bound.name;
        }
        Zustellpunkt z = selectedZustellpunkt();
        p.zustellpunktId = z == null ? null : z.id;
        p.destinationId = selectedDestinationId();
        Connection connection = (Connection) connectionCombo.getSelectedItem();
        Connection zustellpunktDefault = ctx.connectionFor(z, null);
        p.connectionId = connection != null && connection != zustellpunktDefault ? connection.id : null;
        p.serviceId = Ui.blankToNull(text(serviceIdCombo));
        p.serviceName = Ui.blankToNull(serviceName.getText());
        p.serviceRegion = Ui.blankToNull(text(regionCombo));
        p.dataFormat = json.isSelected() ? DataFormat.JSON : DataFormat.XML;
        p.dataSchema = Ui.blankToNull(text(schemaCombo));
        p.metadataVersion = Ui.blankToNull(text(metadataVersionCombo));
        p.replyChannelEmail = Ui.blankToNull(replyEmail.getText());
        p.payloadTemplate = payload.getText();
        p.attachments = new ArrayList<>();
        for (int i = 0; i < attachmentModel.size(); i++) {
            p.attachments.add(attachmentModel.get(i).copy());
        }
        return p;
    }

    private void writeForm(Preset p) {
        loading = true;
        try {
            Zustellpunkt z = ctx.data.zustellpunkt(p.zustellpunktId).orElse(null);
            if (z != null) {
                zustellpunktCombo.setSelectedItem(z);
            } else {
                zustellpunktCombo.setSelectedItem(p.destinationId == null ? "" : p.destinationId.toString());
                z = selectedZustellpunkt();
            }
            Connection connection = ctx.connectionFor(z, p.connectionId);
            connectionCombo.setSelectedItem(connection);
            serviceIdCombo.setSelectedItem(Ui.nullToEmpty(p.serviceId));
            serviceName.setText(Ui.nullToEmpty(p.serviceName));
            regionCombo.setSelectedItem(Ui.nullToEmpty(p.serviceRegion));
            (p.dataFormat == DataFormat.JSON ? json : xml).setSelected(true);
            schemaCombo.setSelectedItem(Ui.nullToEmpty(p.dataSchema));
            metadataVersionCombo.setSelectedItem(Ui.nullToEmpty(p.metadataVersion));
            replyEmail.setText(Ui.nullToEmpty(p.replyChannelEmail));
            payload.setText(Ui.nullToEmpty(p.payloadTemplate));
            payload.setCaretPosition(0);
            attachmentModel.clear();
            p.attachments.forEach(a -> attachmentModel.addElement(a.copy()));
            // Options from the destination info, keeping the values just set.
            onZustellpunktInfoMaybeChanged();
        } finally {
            loading = false;
        }
        updateEnvironmentBadge();
        updateHints();
    }

    /** Opens the form from elsewhere: a record to resend, a Zustellpunkt to send to, a case to follow up. */
    public void compose(Preset form, Zustellpunkt zustellpunkt, UUID caseIdValue) {
        if (form != null) {
            currentPresetId = ctx.data.preset(form.id).isPresent() ? form.id : null;
            writeForm(form);
            reloadPresets();
        }
        if (zustellpunkt != null) {
            zustellpunktCombo.setSelectedItem(zustellpunkt);
        }
        caseId.setText(caseIdValue == null ? "" : caseIdValue.toString());
    }

    // --- attachments ------------------------------------------------------

    private void addAttachments() {
        JFileChooser chooser = new JFileChooser(ctx.data.settings().lastDirectory);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            ctx.data.settings().lastDirectory = chooser.getCurrentDirectory().getPath();
            addFiles(List.of(chooser.getSelectedFiles()));
        }
    }

    private void addFiles(List<File> files) {
        for (File file : files) {
            if (file.isFile()) {
                attachmentModel.addElement(new AttachmentSpec(file.getAbsolutePath(), guessMimeType(file.toPath())));
            }
        }
    }

    static String guessMimeType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                return probed;
            }
        } catch (IOException ignored) {
            // fall through to the extension table
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME_BY_EXTENSION.getOrDefault(extension, "application/octet-stream");
    }

    private void editAttachmentMime() {
        AttachmentSpec selected = attachmentList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String mime = JOptionPane.showInputDialog(this, "MIME type for " + Path.of(selected.path).getFileName(), selected.mimeType);
        if (mime != null && !mime.isBlank()) {
            selected.mimeType = mime.trim();
            attachmentList.repaint();
        }
    }

    private void removeAttachments() {
        for (AttachmentSpec spec : attachmentList.getSelectedValuesList()) {
            attachmentModel.removeElement(spec);
        }
    }

    /** Accepts files dropped onto the attachment list. */
    private class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            try {
                addFiles((List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // --- payload ----------------------------------------------------------

    private DataFormat format() {
        return json.isSelected() ? DataFormat.JSON : DataFormat.XML;
    }

    private void loadPayloadFile() {
        JFileChooser chooser = new JFileChooser(ctx.data.settings().lastDirectory);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        ctx.data.settings().lastDirectory = file.getParent().toString();
        try {
            payload.setText(Files.readString(file, StandardCharsets.UTF_8));
            payload.setCaretPosition(0);
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) {
                json.setSelected(true);
            } else if (name.endsWith(".xml")) {
                xml.setSelected(true);
            }
        } catch (IOException e) {
            Ui.error(this, "Load file", e.getMessage());
        }
    }

    private void prettyPrint() {
        try {
            payload.setText(PayloadTools.prettyPrint(payload.getText(), format()));
            payload.setCaretPosition(0);
        } catch (Exception e) {
            Ui.error(this, "Pretty print", "The payload is not well-formed " + format() + ":\n" + e.getMessage());
        }
    }

    private void checkPayload() {
        // Placeholders are resolved first - ${...} inside JSON strings is fine, but resolve anyway to check the real thing.
        String resolved = PayloadTemplate.resolve(payload.getText(), ctx.data.settings().counter + 1, parseCaseIdQuietly());
        String problem = PayloadTools.checkWellFormed(resolved, format());
        if (problem == null) {
            Ui.info(this, "Check", "The payload is well-formed " + format() + ".");
        } else {
            Ui.error(this, "Check", "Not well-formed " + format() + ":\n" + problem);
        }
    }

    private JPopupMenu placeholderMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (String placeholder : List.of("${uuid}", "${now}", "${date}", "${counter}", "${caseId}")) {
            JMenuItem item = new JMenuItem(placeholder);
            item.addActionListener(e -> {
                payload.replaceSelection(placeholder);
                payload.requestFocusInWindow();
            });
            menu.add(item);
        }
        return menu;
    }

    private void previewPayload() {
        String resolved = PayloadTemplate.resolve(payload.getText(), ctx.data.settings().counter + 1, parseCaseIdQuietly());
        JTextArea area = new JTextArea(resolved, 20, 70);
        area.setFont(Ui.monospaced());
        area.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Preview (next send)", JOptionPane.PLAIN_MESSAGE);
    }

    private void examplePayload() {
        if (!payload.getText().isBlank() && !Ui.confirm(this, "Example", "Replace the current payload?")) {
            return;
        }
        payload.setText(format() == DataFormat.JSON
                ? """
                  {
                    "marker": "fitko-swing-client/${uuid}",
                    "sentAt": "${now}",
                    "counter": ${counter}
                  }
                  """
                : Preset.exampleGewerbeanmeldung().payloadTemplate);
        payload.setCaretPosition(0);
    }

    private UUID parseCaseIdQuietly() {
        try {
            return caseId.getText().isBlank() ? null : UUID.fromString(caseId.getText().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- send -------------------------------------------------------------

    /**
     * The SDK rejects a submission whose schema URI + format (XML/JSON) isn't
     * one the destination lists for the service. With fetched destination info
     * that's checked here first, with an offer to switch to an allowed pair.
     *
     * @return {@code false} to abort the send
     */
    private boolean matchSchemaToDestination(Preset form, Zustellpunkt zustellpunkt) {
        DestinationInfo info = zustellpunkt == null ? null : zustellpunkt.info;
        if (info == null || info.services.isEmpty()) {
            return true; // nothing known - let the SDK decide
        }
        DestinationInfo.ServiceInfo service = info.service(form.serviceId);
        if (service == null) {
            return Ui.confirm(this, "Send", "The Zustellpunkt '" + zustellpunkt.label + "' doesn't list service\n"
                    + form.serviceId + "\n\nIt offers: " + info.services + "\n\nSend anyway?");
        }
        if (service.schemas.isEmpty() || service.schemas.stream()
                .anyMatch(s -> s.schemaUri != null && s.schemaUri.equals(form.dataSchema) && s.dataFormat() == form.dataFormat)) {
            return true;
        }
        DestinationInfo.SchemaInfo allowed = service.schemas.get(0);
        String allowedList = service.schemas.stream()
                .map(s -> s.dataFormat() + " → " + s.schemaUri).collect(java.util.stream.Collectors.joining("\n"));
        int choice = JOptionPane.showOptionDialog(this,
                "The Zustellpunkt doesn't accept this schema/format for the service.\n\n"
                        + "Your form:   " + form.dataFormat + " → " + Ui.nullToEmpty(form.dataSchema) + "\n\n"
                        + "Allowed:\n" + allowedList,
                "Schema doesn't match", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new Object[]{"Use " + allowed.dataFormat() + " + allowed schema", "Send anyway", "Cancel"},
                "Use " + allowed.dataFormat() + " + allowed schema");
        if (choice == 0) {
            (allowed.dataFormat() == DataFormat.JSON ? json : xml).setSelected(true);
            schemaCombo.setSelectedItem(allowed.schemaUri);
            form.dataFormat = allowed.dataFormat();
            form.dataSchema = allowed.schemaUri;
            return true;
        }
        return choice == 1;
    }

    private void send() {
        Preset form = readForm();
        Zustellpunkt zustellpunkt = selectedZustellpunkt();
        UUID destinationId = selectedDestinationId();
        if (destinationId == null) {
            Ui.error(this, "Send", "Pick a Zustellpunkt or paste a destination ID.");
            return;
        }
        Connection connection = requireConnection();
        if (connection == null) {
            return;
        }
        UUID caseIdValue = null;
        if (!caseId.getText().isBlank()) {
            caseIdValue = Ui.findUuid(caseId.getText());
            if (caseIdValue == null) {
                Ui.error(this, "Send", "The case id is not a UUID.");
                return;
            }
        }
        if (form.payloadTemplate == null || form.payloadTemplate.isBlank()) {
            Ui.error(this, "Send", "The payload is empty.");
            return;
        }
        for (AttachmentSpec attachment : form.attachments) {
            if (!Files.isReadable(Path.of(attachment.path))) {
                Ui.error(this, "Send", "Attachment not readable: " + attachment.path);
                return;
            }
        }
        if (!matchSchemaToDestination(form, zustellpunkt)) {
            return;
        }
        String problem = PayloadTools.checkWellFormed(
                PayloadTemplate.resolve(form.payloadTemplate, 0, caseIdValue), form.dataFormat);
        if (problem != null && !Ui.confirm(this, "Send",
                "The payload is not well-formed " + form.dataFormat + ":\n" + problem
                        + "\n\nSend anyway? (Useful to test the receiver's auto-reject.)")) {
            return;
        }
        if (!connection.isTestEnvironment() && !Ui.confirm(this, "Send to " + connection.environment,
                "This sends to the " + connection.environment + " environment, not TEST.\nContinue?")) {
            return;
        }

        int count = (Integer) copies.getValue();
        long[] counters = new long[count];
        for (int i = 0; i < count; i++) {
            counters[i] = ctx.data.nextCounter();
        }
        SendRequest request = new SendRequest(form, zustellpunkt, destinationId, connection, caseIdValue);
        sendButton.setEnabled(false);
        sendResult.setText("Sending…");

        new SwingWorker<Void, SubmissionRecord>() {
            private final List<SubmissionRecord> sent = new ArrayList<>();

            @Override
            protected Void doInBackground() {
                for (long counter : counters) {
                    publish(ctx.sender.send(request, counter));
                }
                return null;
            }

            @Override
            protected void process(List<SubmissionRecord> chunks) {
                for (SubmissionRecord record : chunks) {
                    ctx.data.add(record);
                    sent.add(record);
                }
                sendResult.setText("Sending… " + sent.size() + "/" + count);
            }

            @Override
            protected void done() {
                sendButton.setEnabled(true);
                try {
                    get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Ui.error(ComposePanel.this, "Send failed", e.getCause().toString());
                }
                List<SubmissionRecord> failed = sent.stream().filter(r -> !r.wasSent()).toList();
                int ok = sent.size() - failed.size();
                SubmissionRecord last = sent.isEmpty() ? null : sent.get(sent.size() - 1);
                if (failed.isEmpty() && last != null) {
                    sendResult.setText("Sent " + ok + " submission(s) — last: " + last.submissionId);
                    ctx.navigator.message("Sent " + ok + " submission(s) to " + destinationLabel(last));
                    ctx.navigator.showInHistory(last);
                } else {
                    sendResult.setText(ok + " sent, " + failed.size() + " failed");
                    if (!failed.isEmpty()) {
                        Ui.error(ComposePanel.this, "Send failed", failed.get(0).error);
                    }
                }
            }
        }.execute();
    }

    private static String destinationLabel(SubmissionRecord record) {
        return record.zustellpunktLabel != null ? record.zustellpunktLabel : String.valueOf(record.destinationId);
    }
}
