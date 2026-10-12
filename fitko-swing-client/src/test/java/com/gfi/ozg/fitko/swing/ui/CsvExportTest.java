package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExportTest {

    @Test
    void quotesOnlyWhenNeeded() {
        assertEquals("plain", CsvExport.quote("plain"));
        assertEquals("\"a,b\"", CsvExport.quote("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.quote("say \"hi\""));
        assertEquals("\"two\nlines\"", CsvExport.quote("two\nlines"));
    }

    @Test
    void oneLinePerRecordPlusHeader() {
        SubmissionRecord r = new SubmissionRecord();
        r.status = RecordStatus.ACCEPTED;
        r.note = "looks, good";
        String csv = CsvExport.toCsv(List.of(r, r));
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].startsWith("sentAt,status,"));
        assertTrue(lines[1].contains("ACCEPTED"));
        assertTrue(lines[1].endsWith("\"looks, good\""));
    }
}
