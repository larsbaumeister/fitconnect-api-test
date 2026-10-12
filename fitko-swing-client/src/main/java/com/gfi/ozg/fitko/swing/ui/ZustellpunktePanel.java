package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.DestinationInfo;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;
import com.gfi.ozg.fitko.swing.store.AppData;
import com.gfi.ozg.fitko.swing.store.Json;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The "Zustellpunkte" tab: the saved destinations, their fetched info, import/export. */
public class ZustellpunktePanel extends JPanel {

    private static final String[] COLUMNS = {"Label", "Destination ID", "Connection", "Status", "Services", "Info fetched", "Tags"};

    private final AppContext ctx;
    // Created in the constructor, not as field initializers: the model reads ctx, which is only set there.
    private final Model model;
    private final JTable table;
    private final TableRowSorter<Model> sorter;
    private final JTextArea detail = new JTextArea();
    private final JTextField search = new JTextField(20);
    private final JSplitPane split;

    public ZustellpunktePanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.model = new Model();
        this.table = new JTable(model);
        this.sorter = new TableRowSorter<>(model);

        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(280);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    edit();
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

        detail.setEditable(false);
        detail.setFont(Ui.monospaced());

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(button("Add…", "Add a Zustellpunkt by its destination ID", this::add));
        toolbar.add(button("Edit…", null, this::edit));
        toolbar.add(button("Duplicate", null, this::duplicate));
        toolbar.add(button("Delete", null, this::delete));
        toolbar.addSeparator();
        toolbar.add(button("Fetch info", "Re-read services, schemas and regions of the selected Zustellpunkte", this::fetchSelected));
        toolbar.add(button("Copy ID", null, this::copyId));
        toolbar.add(button("Send to this…", "Open the compose form for the selected Zustellpunkt", this::sendTo));
        toolbar.addSeparator();
        toolbar.add(button("Import…", "Import Zustellpunkte + presets shared by someone else", this::importFile));
        toolbar.add(button("Export…", "Export Zustellpunkte + presets (never secrets) to share them", this::exportFile));

        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        searchBar.add(new JLabel("Search"));
        searchBar.add(search);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar, BorderLayout.NORTH);
        top.add(searchBar, BorderLayout.SOUTH);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.6);
        split.setBorder(BorderFactory.createEmptyBorder());
        if (ctx.data.settings().zustellpunkteSplit > 0) {
            split.setDividerLocation(ctx.data.settings().zustellpunkteSplit);
        }

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        ctx.data.addListener(kind -> {
            if (kind == AppData.Kind.ZUSTELLPUNKTE || kind == AppData.Kind.CONNECTIONS) {
                refresh();
            }
        });
    }

    public int dividerLocation() {
        return split.getDividerLocation();
    }

    private static JButton button(String text, String tooltip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
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
        menu.add(item("Send to this…", this::sendTo));
        menu.add(item("Edit…", this::edit));
        menu.add(item("Fetch info", this::fetchSelected));
        menu.add(item("Copy destination ID", this::copyId));
        menu.add(item("Duplicate", this::duplicate));
        menu.addSeparator();
        menu.add(item("Delete", this::delete));
        menu.show(table, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void refresh() {
        List<String> selectedIds = selected().stream().map(z -> z.id).toList();
        model.fireTableDataChanged();
        for (int i = 0; i < model.getRowCount(); i++) {
            if (selectedIds.contains(model.at(i).id)) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) {
                    table.addRowSelectionInterval(view, view);
                }
            }
        }
        showDetail();
    }

    private void applyFilter() {
        String text = search.getText().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends Model, ? extends Integer> entry) {
                Zustellpunkt z = model.at(entry.getIdentifier());
                String haystack = String.join(" ", z.label, Ui.nullToEmpty(z.destinationId), Ui.nullToEmpty(z.tags),
                        Ui.nullToEmpty(z.notes), ctx.connectionLabel(z.connectionId), describe(z.info));
                return haystack.toLowerCase(Locale.ROOT).contains(text);
            }
        });
    }

    private List<Zustellpunkt> selected() {
        List<Zustellpunkt> result = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            result.add(model.at(table.convertRowIndexToModel(viewRow)));
        }
        return result;
    }

    private Zustellpunkt selectedOne() {
        List<Zustellpunkt> selected = selected();
        return selected.isEmpty() ? null : selected.get(0);
    }

    public void select(Zustellpunkt zustellpunkt) {
        int index = ctx.data.zustellpunkte().indexOf(zustellpunkt);
        if (index >= 0) {
            int view = table.convertRowIndexToView(index);
            if (view >= 0) {
                table.setRowSelectionInterval(view, view);
                table.scrollRectToVisible(table.getCellRect(view, 0, true));
            }
        }
    }

    private void showDetail() {
        Zustellpunkt z = selectedOne();
        if (z == null) {
            detail.setText(ctx.data.zustellpunkte().isEmpty()
                    ? "No Zustellpunkte yet. Click \"Add…\" and paste a destination ID."
                    : "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(z.label).append('\n');
        sb.append("Destination ID: ").append(z.destinationId).append('\n');
        sb.append("Connection:     ").append(ctx.connectionLabel(z.connectionId).isEmpty()
                ? "(default) " + Ui.nullToEmpty(ctx.defaultConnection()) : ctx.connectionLabel(z.connectionId)).append('\n');
        if (z.tags != null) {
            sb.append("Tags:           ").append(z.tags).append('\n');
        }
        if (z.notes != null) {
            sb.append("Notes:          ").append(z.notes.replace("\n", "\n                ")).append('\n');
        }
        long sent = ctx.data.submissions().stream().filter(r -> z.id.equals(r.zustellpunktId)).count();
        sb.append("Sent so far:    ").append(sent).append('\n');
        sb.append('\n').append(describe(z.info));
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
    }

    /** Human-readable summary of fetched destination info. */
    static String describe(DestinationInfo info) {
        if (info == null) {
            return "No destination info fetched yet - click \"Fetch info\".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Status:            ").append(Ui.nullToEmpty(info.status)).append('\n');
        sb.append("Metadata versions: ").append(String.join(", ", info.metadataVersions)).append('\n');
        sb.append("Reply channels:    ").append(info.replyChannels.isEmpty() ? "-" : String.join(", ", info.replyChannels)).append('\n');
        sb.append("Fetched:           ").append(Ui.format(info.fetchedAt)).append('\n');
        sb.append("Services (").append(info.services.size()).append("):\n");
        for (DestinationInfo.ServiceInfo service : info.services) {
            sb.append("  • ").append(service.identifier).append('\n');
            if (!service.regions.isEmpty()) {
                sb.append("      regions: ").append(String.join(", ", service.regions)).append('\n');
            }
            for (DestinationInfo.SchemaInfo schema : service.schemas) {
                sb.append("      schema:  ").append(schema.schemaUri).append("  [").append(schema.mimeType).append("]\n");
            }
        }
        return sb.toString();
    }

    // --- actions ----------------------------------------------------------

    public void add() {
        Zustellpunkt fresh = new Zustellpunkt();
        Connection conn = ctx.defaultConnection();
        fresh.connectionId = conn == null ? null : conn.id;
        addWithDialog(fresh);
    }

    /** Opens the editor for a new Zustellpunkt pre-filled with {@code template}; returns it if saved. */
    public Zustellpunkt addWithDialog(Zustellpunkt template) {
        Zustellpunkt saved = new ZustellpunktDialog(SwingUtilities.getWindowAncestor(this), ctx, template, "Add Zustellpunkt").showDialog();
        if (saved != null) {
            ctx.data.upsert(saved);
            select(saved);
            ctx.navigator.message("Zustellpunkt '" + saved.label + "' added");
        }
        return saved;
    }

    private void edit() {
        Zustellpunkt z = selectedOne();
        if (z == null) {
            return;
        }
        Zustellpunkt saved = new ZustellpunktDialog(SwingUtilities.getWindowAncestor(this), ctx, z, "Edit Zustellpunkt").showDialog();
        if (saved != null) {
            ctx.data.upsert(saved);
            select(saved);
        }
    }

    private void duplicate() {
        Zustellpunkt z = selectedOne();
        if (z == null) {
            return;
        }
        Zustellpunkt copy = z.copy();
        copy.id = UUID.randomUUID().toString();
        copy.label = z.label + " (copy)";
        ctx.data.upsert(copy);
        select(copy);
    }

    private void delete() {
        List<Zustellpunkt> selected = selected();
        if (selected.isEmpty()) {
            return;
        }
        String what = selected.size() == 1 ? "'" + selected.get(0).label + "'" : selected.size() + " Zustellpunkte";
        if (!Ui.confirm(this, "Delete", "Delete " + what + "?\nSent submissions stay in the history.")) {
            return;
        }
        ctx.data.zustellpunkte().removeAll(selected);
        ctx.data.saveZustellpunkte();
    }

    private void fetchSelected() {
        List<Zustellpunkt> selected = selected();
        if (selected.isEmpty()) {
            return;
        }
        for (Zustellpunkt z : selected) {
            Connection conn = ctx.connectionFor(z, null);
            if (conn == null) {
                Ui.error(this, "Fetch info", "Add a connection first (File → Connections).");
                return;
            }
            Ui.async(this, "Fetch info for '" + z.label + "'", () -> ctx.destinations.fetch(conn, z.destinationId), info -> {
                z.info = info;
                ctx.data.saveZustellpunkte();
                ctx.navigator.message("Fetched info for '" + z.label + "': " + info.services.size() + " service(s)");
            });
        }
    }

    private void copyId() {
        Zustellpunkt z = selectedOne();
        if (z != null) {
            Ui.copy(z.destinationId.toString());
            ctx.navigator.message("Copied " + z.destinationId);
        }
    }

    private void sendTo() {
        Zustellpunkt z = selectedOne();
        if (z != null) {
            ctx.navigator.compose(null, z, null);
        }
    }

    // --- import / export --------------------------------------------------

    /** What an export file holds. Connections are deliberately left out - they carry secrets. */
    public static class ShareFile {
        public int version = 1;
        public Instant exportedAt;
        public List<Zustellpunkt> zustellpunkte = new ArrayList<>();
        public List<Preset> presets = new ArrayList<>();
    }

    private JFileChooser chooser() {
        JFileChooser chooser = new JFileChooser(ctx.data.settings().lastDirectory);
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        return chooser;
    }

    private void exportFile() {
        JFileChooser chooser = chooser();
        chooser.setSelectedFile(new File("fitconnect-setup.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        ctx.data.settings().lastDirectory = file.getParent();
        ShareFile share = new ShareFile();
        share.exportedAt = Instant.now();
        share.zustellpunkte = ctx.data.zustellpunkte().stream().map(z -> {
            Zustellpunkt copy = z.copy();
            copy.connectionId = null;
            return copy;
        }).toList();
        share.presets = ctx.data.presets().stream().map(p -> {
            Preset copy = p.copy();
            copy.connectionId = null;
            return copy;
        }).toList();
        try {
            Json.MAPPER.writeValue(file, share);
            ctx.navigator.message("Exported " + share.zustellpunkte.size() + " Zustellpunkte and " + share.presets.size()
                    + " presets to " + file);
        } catch (IOException e) {
            Ui.error(this, "Export failed", e.getMessage());
        }
    }

    private void importFile() {
        JFileChooser chooser = chooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        ctx.data.settings().lastDirectory = file.getParent();
        try {
            ShareFile share = Json.MAPPER.readValue(file, ShareFile.class);
            for (Zustellpunkt z : share.zustellpunkte) {
                if (z.connectionId != null && ctx.data.connection(z.connectionId).isEmpty()) {
                    z.connectionId = null;
                }
                ctx.data.upsert(z);
            }
            for (Preset p : share.presets) {
                if (p.connectionId != null && ctx.data.connection(p.connectionId).isEmpty()) {
                    p.connectionId = null;
                }
                ctx.data.upsert(p);
            }
            Ui.info(this, "Import", "Imported " + share.zustellpunkte.size() + " Zustellpunkte and "
                    + share.presets.size() + " presets.\nThey use your default connection unless you assign another one.");
        } catch (IOException e) {
            Ui.error(this, "Import failed", e.getMessage());
        }
    }

    // --- table model ------------------------------------------------------

    private class Model extends AbstractTableModel {

        Zustellpunkt at(int row) {
            return ctx.data.zustellpunkte().get(row);
        }

        @Override
        public int getRowCount() {
            return ctx.data.zustellpunkte().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 4 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Zustellpunkt z = at(row);
            return switch (column) {
                case 0 -> z.label;
                case 1 -> Ui.nullToEmpty(z.destinationId);
                case 2 -> z.connectionId == null ? "(default)" : ctx.connectionLabel(z.connectionId);
                case 3 -> z.info == null ? "" : Ui.nullToEmpty(z.info.status);
                case 4 -> z.info == null ? null : z.info.services.size();
                case 5 -> z.info == null ? "" : Ui.format(z.info.fetchedAt);
                case 6 -> Ui.nullToEmpty(z.tags);
                default -> "";
            };
        }
    }
}
