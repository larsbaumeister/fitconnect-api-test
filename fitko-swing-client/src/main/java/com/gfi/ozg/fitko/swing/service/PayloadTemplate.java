package com.gfi.ozg.fitko.swing.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves placeholders in a payload template so the same preset can be sent
 * again and again with fresh values:
 *
 * <ul>
 *   <li>{@code ${uuid}} - a new random UUID (one per occurrence)</li>
 *   <li>{@code ${now}} - current timestamp, ISO-8601 with offset</li>
 *   <li>{@code ${date}} - current date, ISO-8601</li>
 *   <li>{@code ${counter}} - the running send counter</li>
 *   <li>{@code ${caseId}} - the case id the submission is appended to (empty for a new case)</li>
 * </ul>
 *
 * Unknown placeholders are left as they are. {@code $${x}} escapes to a literal {@code ${x}}.
 */
public final class PayloadTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$?\\$\\{([a-zA-Z]+)}");

    private PayloadTemplate() {
    }

    public static String resolve(String template, long counter, UUID caseId) {
        return resolve(template, Map.of(
                "uuid", () -> UUID.randomUUID().toString(),
                "now", () -> OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                "date", () -> LocalDate.now().toString(),
                "counter", () -> Long.toString(counter),
                "caseId", () -> caseId == null ? "" : caseId.toString()));
    }

    static String resolve(String template, Map<String, Supplier<String>> values) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            String replacement;
            if (match.startsWith("$$")) {
                replacement = match.substring(1);
            } else {
                Supplier<String> value = values.get(matcher.group(1));
                replacement = value == null ? match : value.get();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean hasPlaceholders(String template) {
        return template != null && PLACEHOLDER.matcher(template).find();
    }
}
