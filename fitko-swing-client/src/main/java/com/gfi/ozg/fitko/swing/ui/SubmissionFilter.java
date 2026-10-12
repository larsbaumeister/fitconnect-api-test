package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/**
 * The history tab's filter, kept free of Swing so it can be tested on its
 * own. A {@code null} criterion matches everything.
 */
public class SubmissionFilter {

    public enum StatusGroup {
        ALL("All statuses"), PENDING("Pending"), ACCEPTED("Accepted"), REJECTED("Rejected"), FAILED("Send failed");

        private final String label;

        StatusGroup(String label) {
            this.label = label;
        }

        boolean matches(RecordStatus status) {
            return switch (this) {
                case ALL -> true;
                case PENDING -> status.isPending();
                case ACCEPTED -> status == RecordStatus.ACCEPTED;
                case REJECTED -> status == RecordStatus.REJECTED;
                case FAILED -> status == RecordStatus.SEND_FAILED;
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Period {
        ALL("Any time", null), TODAY("Today", null), DAYS_7("Last 7 days", Duration.ofDays(7)),
        DAYS_30("Last 30 days", Duration.ofDays(30));

        private final String label;
        private final Duration duration;

        Period(String label, Duration duration) {
            this.label = label;
            this.duration = duration;
        }

        Instant cutoff(Instant now, ZoneId zone) {
            if (this == ALL) {
                return null;
            }
            if (this == TODAY) {
                return LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
            }
            return now.minus(duration);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public String text;
    public StatusGroup statusGroup = StatusGroup.ALL;
    /** A Zustellpunkt label (or destination id for ad-hoc sends). */
    public String zustellpunkt;
    public String preset;
    public Period period = Period.ALL;

    public boolean matches(SubmissionRecord record, Instant now, ZoneId zone) {
        if (statusGroup != null && !statusGroup.matches(record.status)) {
            return false;
        }
        if (zustellpunkt != null && !zustellpunkt.equals(zustellpunktKey(record))) {
            return false;
        }
        if (preset != null && !preset.equals(record.presetName)) {
            return false;
        }
        Instant cutoff = period == null ? null : period.cutoff(now, zone);
        if (cutoff != null && (record.sentAt == null || record.sentAt.isBefore(cutoff))) {
            return false;
        }
        return text == null || text.isBlank() || haystack(record).contains(text.trim().toLowerCase(Locale.ROOT));
    }

    /** What the Zustellpunkt filter compares against. */
    public static String zustellpunktKey(SubmissionRecord record) {
        return record.zustellpunktLabel != null ? record.zustellpunktLabel : Objects.toString(record.destinationId, "");
    }

    /** Everything the free-text search looks at, lower-cased - ids, labels, problems, note and the payload. */
    static String haystack(SubmissionRecord r) {
        StringBuilder sb = new StringBuilder();
        for (Object value : new Object[]{r.status, r.presetName, r.connectionLabel, r.environment, r.zustellpunktLabel,
                r.destinationId, r.submissionId, r.caseId, r.serviceId, r.serviceName, r.serviceRegion, r.dataSchema,
                r.replyChannelEmail, r.error, r.lastCheckError, r.note, r.payload}) {
            if (value != null) {
                sb.append(value).append('\n');
            }
        }
        r.problems.forEach(p -> sb.append(p).append('\n'));
        r.attachments.forEach(a -> sb.append(a.path).append('\n'));
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
