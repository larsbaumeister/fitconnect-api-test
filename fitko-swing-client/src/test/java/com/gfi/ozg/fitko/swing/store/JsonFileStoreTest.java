package com.gfi.ozg.fitko.swing.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gfi.ozg.fitko.swing.model.ProblemInfo;
import com.gfi.ozg.fitko.swing.model.RecordStatus;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileStoreTest {

    @TempDir
    Path dir;

    private JsonFileStore<List<SubmissionRecord>> store() {
        return new JsonFileStore<>(dir.resolve("submissions.json"), new TypeReference<>() { }, ArrayList::new, false);
    }

    @Test
    void missingFileLoadsEmpty() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void roundTripKeepsEverythingNeededForAStatusCheck() {
        SubmissionRecord record = new SubmissionRecord();
        record.sentAt = Instant.parse("2026-10-12T08:15:30Z");
        record.destinationId = UUID.randomUUID();
        record.submissionId = UUID.randomUUID();
        record.caseId = UUID.randomUUID();
        record.authTagMetadata = "meta";
        record.authTagData = "data";
        UUID attachmentId = UUID.randomUUID();
        record.authTagAttachments = Map.of(attachmentId, "att");
        record.status = RecordStatus.REJECTED;
        ProblemInfo problem = new ProblemInfo();
        problem.title = "Schema violation";
        record.problems.add(problem);

        JsonFileStore<List<SubmissionRecord>> store = store();
        store.save(List.of(record));
        SubmissionRecord loaded = store().load().get(0);

        assertEquals(record.sentAt, loaded.sentAt);
        assertEquals(record.submissionId, loaded.submissionId);
        assertEquals(RecordStatus.REJECTED, loaded.status);
        assertEquals("Schema violation", loaded.problems.get(0).title);
        var sent = loaded.toSentSubmission();
        assertEquals(record.caseId, sent.caseId());
        assertEquals("meta", sent.authenticationTags().metadata());
        assertEquals("att", sent.authenticationTags().attachments().get(attachmentId));
    }

    @Test
    void saveLeavesNoTempFilesBehind() throws Exception {
        JsonFileStore<List<SubmissionRecord>> store = store();
        store.save(List.of(new SubmissionRecord()));
        store.save(List.of(new SubmissionRecord(), new SubmissionRecord()));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("submissions.json"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals(2, store().load().size());
    }

    @Test
    void corruptFileIsMovedAsideAndLoadsEmpty() throws Exception {
        Files.writeString(dir.resolve("submissions.json"), "{ not json");
        JsonFileStore<List<SubmissionRecord>> store = store();

        assertTrue(store.load().isEmpty());
        assertNotNull(store.loadWarning());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("submissions.json.corrupt-")));
        }
        assertTrue(Files.notExists(dir.resolve("submissions.json")));

        assertTrue(store.load().isEmpty());
        assertNull(store.loadWarning());
    }
}
