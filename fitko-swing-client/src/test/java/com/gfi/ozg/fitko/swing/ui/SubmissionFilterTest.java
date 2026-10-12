package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.ProblemInfo;
import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionFilterTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private static SubmissionRecord record(RecordStatus status, String zustellpunkt, String preset, Duration age) {
        SubmissionRecord r = new SubmissionRecord();
        r.status = status;
        r.zustellpunktLabel = zustellpunkt;
        r.presetName = preset;
        r.sentAt = NOW.minus(age);
        r.destinationId = UUID.randomUUID();
        r.submissionId = UUID.randomUUID();
        r.payload = "<marker>abc-" + status + "</marker>";
        return r;
    }

    private final SubmissionRecord acceptedToday = record(RecordStatus.ACCEPTED, "Team A", "XML", Duration.ofHours(1));
    private final SubmissionRecord rejectedLastWeek = record(RecordStatus.REJECTED, "Team B", "JSON", Duration.ofDays(5));
    private final SubmissionRecord pendingOld = record(RecordStatus.SUBMITTED, "Team A", null, Duration.ofDays(40));
    private final SubmissionRecord failed = record(RecordStatus.SEND_FAILED, null, "XML", Duration.ofMinutes(5));
    private final List<SubmissionRecord> all = List.of(acceptedToday, rejectedLastWeek, pendingOld, failed);

    private List<SubmissionRecord> apply(SubmissionFilter filter) {
        return all.stream().filter(r -> filter.matches(r, NOW, ZONE)).toList();
    }

    @Test
    void emptyFilterMatchesAll() {
        assertEquals(all, apply(new SubmissionFilter()));
    }

    @Test
    void statusGroups() {
        SubmissionFilter f = new SubmissionFilter();
        f.statusGroup = SubmissionFilter.StatusGroup.PENDING;
        assertEquals(List.of(pendingOld), apply(f));
        f.statusGroup = SubmissionFilter.StatusGroup.REJECTED;
        assertEquals(List.of(rejectedLastWeek), apply(f));
        f.statusGroup = SubmissionFilter.StatusGroup.FAILED;
        assertEquals(List.of(failed), apply(f));
    }

    @Test
    void zustellpunktFallsBackToDestinationIdForAdHocSends() {
        SubmissionFilter f = new SubmissionFilter();
        f.zustellpunkt = "Team A";
        assertEquals(List.of(acceptedToday, pendingOld), apply(f));
        f.zustellpunkt = failed.destinationId.toString();
        assertEquals(List.of(failed), apply(f));
    }

    @Test
    void presetAndPeriod() {
        SubmissionFilter f = new SubmissionFilter();
        f.preset = "XML";
        f.period = SubmissionFilter.Period.TODAY;
        assertEquals(List.of(acceptedToday, failed), apply(f));
        f.preset = null;
        f.period = SubmissionFilter.Period.DAYS_7;
        assertEquals(List.of(acceptedToday, rejectedLastWeek, failed), apply(f));
        f.period = SubmissionFilter.Period.DAYS_30;
        assertFalse(apply(f).contains(pendingOld));
    }

    @Test
    void textSearchIsCaseInsensitiveAndCoversIdsPayloadAndProblems() {
        SubmissionFilter f = new SubmissionFilter();
        f.text = rejectedLastWeek.submissionId.toString().toUpperCase();
        assertEquals(List.of(rejectedLastWeek), apply(f));

        f.text = "ABC-accepted";
        assertEquals(List.of(acceptedToday), apply(f));

        ProblemInfo problem = new ProblemInfo();
        problem.title = "Schema violation";
        rejectedLastWeek.problems.add(problem);
        f.text = "schema viol";
        assertEquals(List.of(rejectedLastWeek), apply(f));
    }

    @Test
    void criteriaCombine() {
        SubmissionFilter f = new SubmissionFilter();
        f.zustellpunkt = "Team A";
        f.statusGroup = SubmissionFilter.StatusGroup.ACCEPTED;
        f.text = "marker";
        assertTrue(apply(f).contains(acceptedToday));
        assertEquals(1, apply(f).size());
    }
}
