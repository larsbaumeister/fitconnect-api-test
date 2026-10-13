package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.DataFormat;
import dev.fitko.fitconnect.sdk.api.OutgoingSubmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionFactoryTest {

    private static final String SERVICE = "urn:de:fim:leika:leistung:99050035001000";
    private static final URI SCHEMA = URI.create("https://fimportal.de/api/v0/leistung-steckbriefe/99050035001000/xzufi");

    @TempDir
    Path dir;

    private static SubmissionDraft draft(DataFormat format, String payload, String region, List<AttachmentSpec> attachments,
                                         String email, UUID caseId) {
        return new SubmissionDraft(UUID.randomUUID(), SERVICE, "Gewerbeanmeldung", region, format, SCHEMA, payload,
                attachments, email, caseId);
    }

    @Test
    void minimalXmlSubmission() {
        SubmissionDraft d = draft(DataFormat.XML, "<a/>", null, List.of(), null, null);
        OutgoingSubmission s = SubmissionFactory.build(d);

        assertEquals(d.destinationId(), s.getDestinationId());
        assertEquals("<a/>", new String(s.getData(), StandardCharsets.UTF_8));
        assertEquals(SCHEMA, s.getSubmissionSchema().schemaUri());
        assertTrue(s.getSubmissionSchema().mimeType().toString().toLowerCase().contains("xml"));
        assertEquals(SERVICE, s.getServiceType().identifier());
        assertNull(s.getServiceRegion());
        assertNull(s.getCaseReference());
        assertNull(s.getReplyChannel());
        assertTrue(s.getAttachments().isEmpty());
    }

    @Test
    void jsonWithEverythingOptional() throws Exception {
        Path pdf = Files.write(dir.resolve("antrag.pdf"), new byte[]{1, 2, 3});
        UUID caseId = UUID.randomUUID();
        SubmissionDraft d = draft(DataFormat.JSON, "{\"a\":1}", "DE09", List.of(new AttachmentSpec(pdf.toString(), "application/pdf")),
                "test@example.org", caseId);

        OutgoingSubmission s = SubmissionFactory.build(d);

        assertTrue(s.getSubmissionSchema().mimeType().toString().toLowerCase().contains("json"));
        assertEquals("DE09", s.getServiceRegion());
        assertEquals(caseId, s.getCaseReference().caseId());
        assertNotNull(s.getReplyChannel());
        assertEquals("test@example.org", s.getReplyChannel().email().address());
        assertEquals(1, s.getAttachments().size());
        assertEquals("antrag.pdf", s.getAttachments().get(0).getFileName());
        assertEquals("application/pdf", s.getAttachments().get(0).getMimeType());
    }

    @Test
    void missingRequiredFieldsAreRejectedWithAReadableMessage() {
        SubmissionDraft noDestination = new SubmissionDraft(null, SERVICE, "x", null, DataFormat.XML, SCHEMA, "<a/>",
                List.of(), null, null);
        assertEquals("Destination (Zustellpunkt) is missing",
                assertThrows(IllegalArgumentException.class, () -> SubmissionFactory.build(noDestination)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> SubmissionFactory.build(draft(DataFormat.XML, " ", null, List.of(), null, null)));
    }
}
