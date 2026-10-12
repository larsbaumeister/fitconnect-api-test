package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;

import javax.swing.table.AbstractTableModel;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The history table over the stored submission records. */
public class SubmissionTableModel extends AbstractTableModel {

    public static final int COL_SENT = 0;
    public static final int COL_STATUS = 1;

    private static final String[] COLUMNS = {"Sent", "Status", "Zustellpunkt", "Connection", "Preset", "Service",
            "Submission ID", "Case ID", "Last checked", "Note"};
    private static final long FLASH_MILLIS = 6000;

    private final Supplier<List<SubmissionRecord>> records;
    /** Records whose status just changed, with the time it changed - rendered highlighted for a few seconds. */
    private final Map<String, Long> flashing = new HashMap<>();

    public SubmissionTableModel(Supplier<List<SubmissionRecord>> records) {
        this.records = records;
    }

    public SubmissionRecord at(int modelRow) {
        return records.get().get(modelRow);
    }

    public int indexOf(SubmissionRecord record) {
        return records.get().indexOf(record);
    }

    public void flash(SubmissionRecord record) {
        flashing.put(record.id, System.currentTimeMillis());
    }

    public boolean isFlashing(int modelRow) {
        Long since = flashing.get(at(modelRow).id);
        return since != null && System.currentTimeMillis() - since < FLASH_MILLIS;
    }

    /** Drops expired highlights; returns whether any are left. */
    public boolean expireFlashes() {
        long now = System.currentTimeMillis();
        flashing.values().removeIf(since -> now - since >= FLASH_MILLIS);
        return !flashing.isEmpty();
    }

    @Override
    public int getRowCount() {
        return records.get().size();
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
        return switch (column) {
            case COL_SENT, 8 -> Instant.class;
            case COL_STATUS -> RecordStatus.class;
            default -> String.class;
        };
    }

    @Override
    public Object getValueAt(int row, int column) {
        SubmissionRecord r = at(row);
        return switch (column) {
            case COL_SENT -> r.sentAt;
            case COL_STATUS -> r.status;
            case 2 -> SubmissionFilter.zustellpunktKey(r);
            case 3 -> r.connectionLabel == null ? "" : r.connectionLabel + " (" + r.environment + ")";
            case 4 -> Ui.nullToEmpty(r.presetName);
            case 5 -> r.serviceName == null ? Ui.nullToEmpty(r.serviceId) : r.serviceName;
            case 6 -> Ui.nullToEmpty(r.submissionId);
            case 7 -> Ui.nullToEmpty(r.caseId);
            case 8 -> r.lastCheckedAt;
            case 9 -> Ui.nullToEmpty(r.note);
            default -> "";
        };
    }
}
