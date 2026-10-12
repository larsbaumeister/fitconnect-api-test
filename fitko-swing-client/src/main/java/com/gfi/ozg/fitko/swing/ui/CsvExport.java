package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.ProblemInfo;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** History rows as CSV (RFC 4180 quoting). */
public final class CsvExport {

    private static final String[] HEADER = {"sentAt", "status", "statusIssuedAt", "problems", "zustellpunkt",
            "destinationId", "connection", "environment", "preset", "serviceId", "serviceName", "submissionId",
            "caseId", "lastCheckedAt", "error", "note"};

    private CsvExport() {
    }

    public static String toCsv(List<SubmissionRecord> records) {
        StringBuilder sb = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (SubmissionRecord r : records) {
            Object[] values = {r.sentAt, r.status, r.statusIssuedAt,
                    r.problems.stream().map(ProblemInfo::toString).collect(Collectors.joining("; ")),
                    r.zustellpunktLabel, r.destinationId, r.connectionLabel, r.environment, r.presetName, r.serviceId,
                    r.serviceName, r.submissionId, r.caseId, r.lastCheckedAt, r.error, r.note};
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(Objects.toString(values[i], "")));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String quote(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
