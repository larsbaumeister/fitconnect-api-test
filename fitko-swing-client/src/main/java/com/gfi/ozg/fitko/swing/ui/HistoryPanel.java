package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.ProblemInfo;
import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.store.AppData;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The "Sent submissions" tab: every submission this client sent, filterable
 * and searchable, with status refresh, event log, resend and follow-up.
 */
public class HistoryPanel extends JPanel {

    private static final String ALL_ZUSTELLPUNKTE = "All Zustellpunkte";
    private static final String ALL_PRESETS = "All presets";

    private final AppContext ctx;
    private final SubmissionTableModel model;
    private final JTable table;
    private final TableRowSorter<SubmissionTableModel> sorter;
    private final JTextField search = new JTextField(22);
    private final JComboBox<SubmissionFilter.StatusGroup> statusFilter = new JComboBox<>(SubmissionFilter.StatusGroup.values());
    private final DefaultComboBoxModel<String> zustellpunktFilterModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> zustellpunktFilter = new JComboBox<>(zustellpunktFilterModel);
    private final DefaultComboBoxModel<String> presetFilterModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> presetFilter = new JComboBox<>(presetFilterModel);
    private final JComboBox<SubmissionFilter.Period> periodFilter = new JComboBox<>(SubmissionFilter.Period.values());
    private final JLabel countLabel = new JLabel();
    private final JTextArea detail = new JTextArea();
    private final JSplitPane split;
    private final Timer searchDebounce;
    private final Timer flashTimer;
    private SubmissionFilter filter = new SubmissionFilter();
    private boolean rebuildingFilters;

    public HistoryPanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.model = new SubmissionTableModel(ctx.data::submissions);

        table = new JTable(model) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                int modelRow = convertRowIndexToModel(row);
                if (!isRowSelected(row) && convertColumnIndexToModel(column) != SubmissionTableModel.COL_STATUS
                        && model.isFlashing(modelRow)) {
                    c.setBackground(Ui.flashColor());
                }
                return c;
            }
        };
        sorter = new TableRowSorter<>(model);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(SubmissionTableModel.COL_SENT, SortOrder.DESCENDING)));
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDefaultRenderer(Instant.class, new InstantCellRenderer());
        table.setDefaultRenderer(RecordStatus.class, new StatusCellRenderer());
        table.setRowHeight(Math.max(table.getRowHeight(), 24));
        int[] widths = {140, 110, 200, 160, 160, 160, 270, 270, 140, 200};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    showEventLog();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::delete);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refresh", this::refreshSelected);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "eventLog", this::showEventLog);

        detail.setEditable(false);
        detail.setFont(Ui.monospaced());

        // --- toolbar ---
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(button("Refresh status", "Check the status of the selected submissions now (F5)", this::refreshSelected));
        toolbar.add(button("Refresh all pending", "Check every submission that is not accepted/rejected yet", this::refreshAllPending));
        toolbar.add(button("Event log…", "Show the full event log (double-click / Enter)", this::showEventLog));
        toolbar.addSeparator();
        toolbar.add(button("Resend…", "Open the compose form with this submission's settings and payload", this::resend));
        toolbar.add(button("Follow-up…", "Send another submission into the same case", this::followUp));
        toolbar.addSeparator();
        JButton copy = new JButton("Copy ▾");
        copy.addActionListener(e -> copyMenu().show(copy, 0, copy.getHeight()));
        toolbar.add(copy);
        toolbar.add(button("Note…", "Add a note to the selected submission", this::editNote));
        toolbar.add(button("Delete", "Remove the selected submissions from the history (Del)", this::delete));
        toolbar.addSeparator();
        toolbar.add(button("Export CSV…", "Export the rows currently shown", this::exportCsv));

        // --- filter bar ---
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        search.setToolTipText("Searches ids, labels, service, problems, notes and the payload");
        search.putClientProperty("JTextField.placeholderText", "Search…");
        search.putClientProperty("JTextField.showClearButton", true);
        filterBar.add(new JLabel("Search"));
        filterBar.add(search);
        filterBar.add(statusFilter);
        filterBar.add(zustellpunktFilter);
        filterBar.add(presetFilter);
        filterBar.add(periodFilter);
        filterBar.add(button("Clear filters", null, this::clearFilters));
        filterBar.add(countLabel);

        searchDebounce = new Timer(200, e -> applyFilter());
        searchDebounce.setRepeats(false);
        search.getDocument().addDocumentListener(SimpleDocumentListener.of(searchDebounce::restart));
        statusFilter.addActionListener(e -> applyFilter());
        zustellpunktFilter.addActionListener(e -> applyFilter());
        presetFilter.addActionListener(e -> applyFilter());
        periodFilter.addActionListener(e -> applyFilter());

        flashTimer = new Timer(500, e -> {
            if (!model.expireFlashes()) {
                ((Timer) e.getSource()).stop();
            }
            table.repaint();
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar, BorderLayout.NORTH);
        top.add(filterBar, BorderLayout.SOUTH);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.65);
        split.setBorder(BorderFactory.createEmptyBorder());
        if (ctx.data.settings().historySplit > 0) {
            split.setDividerLocation(ctx.data.settings().historySplit);
        }

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        rebuildFilterOptions();
        applyFilter();
        ctx.data.addListener(kind -> {
            if (kind == AppData.Kind.SUBMISSIONS) {
                refreshTable();
            }
        });
    }

    public int dividerLocation() {
        return split.getDividerLocation();
    }

    private void bindKey(KeyStroke key, String name, Runnable action) {
        table.getInputMap(JTable.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        table.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private static JButton button(String text, String tooltip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private JPopupMenu copyMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Submission ID", () -> copy(r -> Objects.toString(r.submissionId, ""))));
        menu.add(item("Case ID", () -> copy(r -> Objects.toString(r.caseId, ""))));
        menu.add(item("Destination ID", () -> copy(r -> Objects.toString(r.destinationId, ""))));
        menu.add(item("Payload", () -> copy(r -> Ui.nullToEmpty(r.payload))));
        menu.add(item("Details", () -> copy(HistoryPanel::describe)));
        return menu;
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Refresh status", this::refreshSelected));
        menu.add(item("Event log…", this::showEventLog));
        menu.addSeparator();
        menu.add(item("Resend…", this::resend));
        menu.add(item("Follow-up in same case…", this::followUp));
        menu.addSeparator();
        JMenu copy = new JMenu("Copy");
        for (Component c : copyMenu().getComponents()) {
            copy.add((JMenuItem) c);
        }
        menu.add(copy);
        menu.add(item("Note…", this::editNote));
        menu.add(item("Show only this Zustellpunkt", () -> {
            SubmissionRecord r = selectedOne();
            if (r != null) {
                zustellpunktFilter.setSelectedItem(SubmissionFilter.zustellpunktKey(r));
            }
        }));
        menu.add(item("Show this case", () -> {
            SubmissionRecord r = selectedOne();
            if (r != null && r.caseId != null) {
                search.setText(r.caseId.toString());
            }
        }));
        menu.addSeparator();
        menu.add(item("Delete", this::delete));
        menu.show(table, e.getX(), e.getY());
    }

    // --- table / filter ---------------------------------------------------

    private List<SubmissionRecord> selected() {
        List<SubmissionRecord> result = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            result.add(model.at(table.convertRowIndexToModel(viewRow)));
        }
        return result;
    }

    private SubmissionRecord selectedOne() {
        List<SubmissionRecord> selected = selected();
        return selected.isEmpty() ? null : selected.get(0);
    }

    private void refreshTable() {
        Set<String> selectedIds = selected().stream().map(r -> r.id).collect(Collectors.toSet());
        model.fireTableDataChanged();
        reselect(selectedIds);
        rebuildFilterOptions();
        updateCount();
        showDetail();
    }

    private void reselect(Set<String> ids) {
        table.clearSelection();
        List<SubmissionRecord> records = ctx.data.submissions();
        for (int i = 0; i < records.size(); i++) {
            if (ids.contains(records.get(i).id)) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) {
                    table.addRowSelectionInterval(view, view);
                }
            }
        }
    }

    private void rebuildFilterOptions() {
        rebuildingFilters = true;
        try {
            Object zustellpunkt = zustellpunktFilter.getSelectedItem();
            Set<String> zustellpunkte = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            Set<String> presets = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (SubmissionRecord r : ctx.data.submissions()) {
                zustellpunkte.add(SubmissionFilter.zustellpunktKey(r));
                if (r.presetName != null) {
                    presets.add(r.presetName);
                }
            }
            zustellpunktFilterModel.removeAllElements();
            zustellpunktFilterModel.addElement(ALL_ZUSTELLPUNKTE);
            zustellpunkte.forEach(zustellpunktFilterModel::addElement);
            zustellpunktFilter.setSelectedItem(zustellpunkt != null && zustellpunkte.contains(zustellpunkt.toString())
                    ? zustellpunkt : ALL_ZUSTELLPUNKTE);

            Object preset = presetFilter.getSelectedItem();
            presetFilterModel.removeAllElements();
            presetFilterModel.addElement(ALL_PRESETS);
            presets.forEach(presetFilterModel::addElement);
            presetFilter.setSelectedItem(preset != null && presets.contains(preset.toString()) ? preset : ALL_PRESETS);
        } finally {
            rebuildingFilters = false;
        }
    }

    private void applyFilter() {
        if (rebuildingFilters) {
            return;
        }
        SubmissionFilter f = new SubmissionFilter();
        f.text = search.getText();
        f.statusGroup = (SubmissionFilter.StatusGroup) statusFilter.getSelectedItem();
        Object zustellpunkt = zustellpunktFilter.getSelectedItem();
        f.zustellpunkt = zustellpunkt == null || ALL_ZUSTELLPUNKTE.equals(zustellpunkt) ? null : zustellpunkt.toString();
        Object preset = presetFilter.getSelectedItem();
        f.preset = preset == null || ALL_PRESETS.equals(preset) ? null : preset.toString();
        f.period = (SubmissionFilter.Period) periodFilter.getSelectedItem();
        filter = f;
        ZoneId zone = ZoneId.systemDefault();
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends SubmissionTableModel, ? extends Integer> entry) {
                return filter.matches(model.at(entry.getIdentifier()), Instant.now(), zone);
            }
        });
        updateCount();
    }

    private void clearFilters() {
        search.setText("");
        statusFilter.setSelectedItem(SubmissionFilter.StatusGroup.ALL);
        zustellpunktFilter.setSelectedItem(ALL_ZUSTELLPUNKTE);
        presetFilter.setSelectedItem(ALL_PRESETS);
        periodFilter.setSelectedItem(SubmissionFilter.Period.ALL);
        applyFilter();
    }

    private void updateCount() {
        countLabel.setText("showing " + table.getRowCount() + " of " + model.getRowCount());
    }

    /** Shows {@code record} - clearing filters that would hide it - and selects it. */
    public void select(SubmissionRecord record) {
        int index = model.indexOf(record);
        if (index < 0) {
            return;
        }
        if (table.convertRowIndexToView(index) < 0) {
            clearFilters();
        }
        int view = table.convertRowIndexToView(index);
        if (view >= 0) {
            table.setRowSelectionInterval(view, view);
            table.scrollRectToVisible(table.getCellRect(view, 0, true));
        }
    }

    /** Called after a status check result was applied. */
    public void statusChanged(SubmissionRecord record) {
        model.flash(record);
        flashTimer.start();
    }

    // --- detail -----------------------------------------------------------

    private void showDetail() {
        List<SubmissionRecord> selected = selected();
        if (selected.size() == 1) {
            detail.setText(describe(selected.get(0)));
            detail.setCaretPosition(0);
        } else if (selected.size() > 1) {
            detail.setText(selected.size() + " submissions selected");
        } else {
            detail.setText(ctx.data.submissions().isEmpty() ? "Nothing sent yet." : "");
        }
    }

    static String describe(SubmissionRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Status:         ").append(r.status);
        if (r.statusIssuedAt != null) {
            sb.append("  (").append(Ui.format(r.statusIssuedAt)).append(r.statusIssuer == null ? "" : " by " + r.statusIssuer).append(')');
        }
        sb.append('\n');
        if (!r.problems.isEmpty()) {
            sb.append("Problems:\n");
            for (ProblemInfo p : r.problems) {
                sb.append("  • ").append(p).append('\n');
            }
        }
        if (r.error != null) {
            sb.append("Send error:     ").append(r.error.replace("\n", "\n                ")).append('\n');
        }
        sb.append("Last checked:   ").append(Ui.format(r.lastCheckedAt));
        if (r.lastCheckError != null) {
            sb.append("  ⚠ ").append(r.lastCheckError.lines().findFirst().orElse(""));
        }
        sb.append('\n');
        sb.append("Sent:           ").append(Ui.format(r.sentAt)).append('\n');
        sb.append("Submission ID:  ").append(Ui.nullToEmpty(r.submissionId)).append('\n');
        sb.append("Case ID:        ").append(Ui.nullToEmpty(r.caseId)).append('\n');
        sb.append("Destination:    ").append(Ui.nullToEmpty(r.destinationId))
                .append(r.zustellpunktLabel == null ? "" : "  (" + r.zustellpunktLabel + ")").append('\n');
        if (r.fromDestinationId != null) {
            sb.append("From:           ").append(r.fromDestinationId).append('\n');
        }
        sb.append("Connection:     ").append(Ui.nullToEmpty(r.connectionLabel)).append(" (").append(Ui.nullToEmpty(r.environment)).append(")\n");
        if (r.presetName != null) {
            sb.append("Preset:         ").append(r.presetName).append('\n');
        }
        sb.append("Service:        ").append(Ui.nullToEmpty(r.serviceId)).append("  ").append(Ui.nullToEmpty(r.serviceName));
        if (r.serviceRegion != null) {
            sb.append("  region ").append(r.serviceRegion);
        }
        sb.append('\n');
        sb.append("Data:           ").append(Ui.nullToEmpty(r.dataFormat)).append("  ").append(Ui.nullToEmpty(r.dataSchema)).append('\n');
        if (r.metadataVersion != null) {
            sb.append("Metadata:       ").append(r.metadataVersion).append('\n');
        }
        if (r.replyChannelEmail != null) {
            sb.append("Reply e-mail:   ").append(r.replyChannelEmail).append('\n');
        }
        for (AttachmentSpec a : r.attachments) {
            sb.append("Attachment:     ").append(a).append('\n');
        }
        if (r.note != null) {
            sb.append("Note:           ").append(r.note).append('\n');
        }
        sb.append("\n--- Payload ---\n").append(Ui.nullToEmpty(r.payload));
        return sb.toString();
    }

    // --- actions ----------------------------------------------------------

    private void refreshSelected() {
        List<SubmissionRecord> records = selected().stream().filter(SubmissionRecord::wasSent).toList();
        if (records.isEmpty()) {
            return;
        }
        ctx.navigator.message("Checking status of " + records.size() + " submission(s)…");
        ctx.poller.checkNow(records);
    }

    private void refreshAllPending() {
        List<SubmissionRecord> pending = ctx.data.submissions().stream()
                .filter(r -> r.wasSent() && r.status.isPending()).toList();
        if (pending.isEmpty()) {
            ctx.navigator.message("No pending submissions");
            return;
        }
        ctx.navigator.message("Checking status of " + pending.size() + " pending submission(s)…");
        ctx.poller.checkNow(pending);
    }

    private void showEventLog() {
        SubmissionRecord r = selectedOne();
        if (r == null) {
            return;
        }
        if (!r.wasSent()) {
            Ui.info(this, "Event log", "This submission was never sent, so there is no event log.\n\n" + Ui.nullToEmpty(r.error));
            return;
        }
        new EventLogDialog(SwingUtilities.getWindowAncestor(this), ctx, r).setVisible(true);
    }

    /** The record's settings as an (unsaved) compose form. */
    static Preset toForm(SubmissionRecord r, AppData data) {
        Preset p = new Preset();
        p.id = UUID.randomUUID().toString();
        p.name = "";
        p.zustellpunktId = r.zustellpunktId != null && data.zustellpunkt(r.zustellpunktId).isPresent() ? r.zustellpunktId : null;
        p.destinationId = r.destinationId;
        p.connectionId = r.connectionId;
        p.serviceId = r.serviceId;
        p.serviceName = r.serviceName;
        p.serviceRegion = r.serviceRegion;
        if (r.dataFormat != null) {
            p.dataFormat = r.dataFormat;
        }
        p.dataSchema = r.dataSchema;
        p.payloadTemplate = r.payload;
        p.attachments = new ArrayList<>(r.attachments.stream().map(AttachmentSpec::copy).toList());
        p.replyChannelEmail = r.replyChannelEmail;
        return p;
    }

    private void resend() {
        SubmissionRecord r = selectedOne();
        if (r != null) {
            ctx.navigator.compose(toForm(r, ctx.data), null, null);
            ctx.navigator.message("Loaded submission " + r.submissionId + " into the form - review and send");
        }
    }

    private void followUp() {
        SubmissionRecord r = selectedOne();
        if (r == null) {
            return;
        }
        if (r.caseId == null) {
            Ui.info(this, "Follow-up", "This submission has no case id (it was never sent).");
            return;
        }
        ctx.navigator.compose(toForm(r, ctx.data), null, r.caseId);
        ctx.navigator.message("Compose a follow-up in case " + r.caseId);
    }

    private void copy(java.util.function.Function<SubmissionRecord, String> value) {
        List<SubmissionRecord> selected = selected();
        if (!selected.isEmpty()) {
            Ui.copy(selected.stream().map(value).collect(Collectors.joining("\n")));
            ctx.navigator.message("Copied " + selected.size() + " value(s)");
        }
    }

    private void editNote() {
        SubmissionRecord r = selectedOne();
        if (r == null) {
            return;
        }
        String note = (String) JOptionPane.showInputDialog(this, "Note", "Note", JOptionPane.PLAIN_MESSAGE, null, null,
                Ui.nullToEmpty(r.note));
        if (note != null) {
            r.note = Ui.blankToNull(note);
            ctx.data.saveSubmissions();
        }
    }

    private void delete() {
        List<SubmissionRecord> selected = selected();
        if (selected.isEmpty()) {
            return;
        }
        if (!Ui.confirm(this, "Delete", "Remove " + selected.size() + " submission(s) from the history?\n"
                + "This only forgets them locally - nothing is deleted on FIT-Connect.")) {
            return;
        }
        ctx.data.submissions().removeAll(selected);
        ctx.data.saveSubmissions();
    }

    public void exportCsv() {
        JFileChooser chooser = new JFileChooser(ctx.data.settings().lastDirectory);
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        chooser.setSelectedFile(new File("fitconnect-submissions.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        ctx.data.settings().lastDirectory = file.getParent();
        List<SubmissionRecord> rows = new ArrayList<>();
        for (int view = 0; view < table.getRowCount(); view++) {
            rows.add(model.at(table.convertRowIndexToModel(view)));
        }
        try {
            Files.writeString(file.toPath(), CsvExport.toCsv(rows), StandardCharsets.UTF_8);
            ctx.navigator.message("Exported " + rows.size() + " row(s) to " + file);
        } catch (IOException e) {
            Ui.error(this, "Export failed", e.getMessage());
        }
    }
}
