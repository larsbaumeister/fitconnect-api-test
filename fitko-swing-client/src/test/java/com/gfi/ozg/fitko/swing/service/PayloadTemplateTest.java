package com.gfi.ozg.fitko.swing.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadTemplateTest {

    @Test
    void resolvesCounterDateAndCase() {
        UUID caseId = UUID.randomUUID();
        String out = PayloadTemplate.resolve("<n>${counter}</n><d>${date}</d><c>${caseId}</c>", 42, caseId);
        assertEquals("<n>42</n><d>" + LocalDate.now() + "</d><c>" + caseId + "</c>", out);
    }

    @Test
    void everyUuidOccurrenceIsFresh() {
        String[] parts = PayloadTemplate.resolve("${uuid}|${uuid}", 1, null).split("\\|");
        assertDoesNotThrow(() -> UUID.fromString(parts[0]));
        assertNotEquals(parts[0], parts[1]);
    }

    @Test
    void caseIdIsEmptyForANewCase() {
        assertEquals("case=", PayloadTemplate.resolve("case=${caseId}", 1, null));
    }

    @Test
    void unknownPlaceholdersAndEscapesAreKept() {
        assertEquals("${foo} and ${uuid}", PayloadTemplate.resolve("${foo} and $${uuid}", 1, null));
    }

    @Test
    void replacementTextIsNotInterpretedAsRegex() {
        // A '$' or '\' in a value must not break Matcher.appendReplacement.
        assertEquals("a$1\\b", PayloadTemplate.resolve("${x}", java.util.Map.of("x", () -> "a$1\\b")));
    }

    @Test
    void nowIsIsoWithOffset() {
        String now = PayloadTemplate.resolve("${now}", 1, null);
        assertDoesNotThrow(() -> java.time.OffsetDateTime.parse(now));
    }

    @Test
    void nullAndDetection() {
        assertNull(PayloadTemplate.resolve(null, 1, null));
        assertTrue(PayloadTemplate.hasPlaceholders("x ${uuid}"));
        assertFalse(PayloadTemplate.hasPlaceholders("plain"));
    }
}
