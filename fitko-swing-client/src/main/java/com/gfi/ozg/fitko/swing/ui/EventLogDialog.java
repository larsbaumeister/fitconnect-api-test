package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.ProblemInfo;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.service.SenderService;
import dev.fitko.fitconnect.api.domain.model.event.EventLogEntry;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** The submission's event log (create, submit, notify, accept/reject, ...) as a timeline. */
public class EventLogDialog extends JDialog {

    private static final String[] COLUMNS = {"Time", "Event", "State", "Issuer", "Problems"};

    private final AppContext ctx;
    private final SubmissionRecord record;
    private final List<EventLogEntry> entries = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextArea detail = new JTextArea();
    private final JLabel status = new JLabel("Loading event log…");

    public EventLogDialog(Window owner, AppContext ctx, SubmissionRecord record) {
        super(owner, "Event log — " + record.submissionId, ModalityType.MODELESS);
        this.ctx = ctx;
        this.record = record;

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setCellRenderer(new InstantCellRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getSelectionModel().addListSelectionListener(e -> showDetail());
        detail.setEditable(false);
        detail.setFont(Ui.monospaced());
        detail.setLineWrap(true);

        JLabel header = new JLabel("<html><b>" + (record.zustellpunktLabel == null ? "" : record.zustellpunktLabel + " · ")
                + record.destinationId + "</b><br>submission " + record.submissionId + " · case " + record.caseId + "</html>");
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.6);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton reload = new JButton("Reload");
        reload.addActionListener(e -> load());
        JButton copy = new JButton("Copy as text");
        copy.addActionListener(e -> Ui.copy(asText()));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(reload);
        buttons.add(copy);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);

        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        setSize(new Dimension(900, 520));
        setLocationRelativeTo(owner);
        load();
    }

    private void load() {
        status.setText("Loading event log…");
        new SwingWorker<List<EventLogEntry>, Void>() {
            @Override
            protected List<EventLogEntry> doInBackground() {
                return ctx.sender.eventLog(record);
            }

            @Override
            protected void done() {
                try {
                    List<EventLogEntry> loaded = new ArrayList<>(get());
                    loaded.sort(Comparator.comparing(EventLogEntry::getIssueTime, Comparator.nullsLast(Comparator.naturalOrder())));
                    entries.clear();
                    entries.addAll(loaded);
                    model.fireTableDataChanged();
                    status.setText(entries.size() + " event(s)");
                    if (!entries.isEmpty()) {
                        table.setRowSelectionInterval(entries.size() - 1, entries.size() - 1);
                    }
                } catch (ExecutionException e) {
                    status.setText("Failed: " + SenderService.describe(e.getCause()).lines().findFirst().orElse(""));
                    detail.setText(SenderService.describe(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void showDetail() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        detail.setText(describe(entries.get(table.convertRowIndexToModel(row))));
        detail.setCaretPosition(0);
    }

    private static String describe(EventLogEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Event:      ").append(e.getEvent()).append('\n');
        if (e.getEvent() != null) {
            sb.append("Schema:     ").append(e.getEvent().getSchemaUri()).append('\n');
            sb.append("State:      ").append(e.getEvent().getState()).append('\n');
        }
        sb.append("Issued:     ").append(e.getIssueTime() == null ? "" : Ui.format(e.getIssueTime().toInstant())).append('\n');
        sb.append("Issuer:     ").append(Ui.nullToEmpty(e.getIssuer())).append('\n');
        sb.append("Event ID:   ").append(Ui.nullToEmpty(e.getEventId())).append('\n');
        sb.append("Case ID:    ").append(Ui.nullToEmpty(e.getCaseId())).append('\n');
        sb.append("Submission: ").append(Ui.nullToEmpty(e.getSubmissionId())).append('\n');
        if (e.getProblems() != null && !e.getProblems().isEmpty()) {
            sb.append("\nProblems:\n");
            for (Problem p : e.getProblems()) {
                sb.append("  • ").append(ProblemInfo.of(p)).append('\n');
            }
        }
        return sb.toString();
    }

    private String asText() {
        StringBuilder sb = new StringBuilder();
        for (EventLogEntry e : entries) {
            sb.append(describe(e)).append("\n");
        }
        return sb.toString();
    }

    private class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return entries.size();
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
        public Object getValueAt(int row, int column) {
            EventLogEntry e = entries.get(row);
            return switch (column) {
                case 0 -> e.getIssueTime() == null ? null : e.getIssueTime().toInstant();
                case 1 -> e.getEvent();
                case 2 -> e.getEvent() == null ? "" : e.getEvent().getState();
                case 3 -> e.getIssuer();
                case 4 -> e.getProblems() == null || e.getProblems().isEmpty() ? ""
                        : e.getProblems().stream().map(p -> ProblemInfo.of(p).toString()).reduce((a, b) -> a + "; " + b).orElse("");
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Instant.class : Object.class;
        }
    }
}
