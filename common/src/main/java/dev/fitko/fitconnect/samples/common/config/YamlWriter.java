package dev.fitko.fitconnect.samples.common.config;

import java.util.List;

/**
 * Tiny helper for assembling the small, flat YAML documents the FIT-Connect
 * SDK expects ({@code ApplicationConfigLoader.loadConfigFromYamlString}).
 * This is not a general-purpose YAML writer: it only supports the handful of
 * constructs (nested maps, quoted scalars, quoted lists) that appear in the
 * SDK's own configuration schema.
 */
public final class YamlWriter {

    private static final String INDENT = "  ";

    private final StringBuilder sb = new StringBuilder();

    public YamlWriter line(int indentLevel, String rawText) {
        sb.append(INDENT.repeat(indentLevel)).append(rawText).append('\n');
        return this;
    }

    public YamlWriter keyValue(int indentLevel, String key, String value) {
        return line(indentLevel, key + ": " + quote(value));
    }

    public YamlWriter keyValueRaw(int indentLevel, String key, String rawValue) {
        return line(indentLevel, key + ": " + rawValue);
    }

    /** Writes a {@code "key": "value"} map entry, quoting both sides. */
    public YamlWriter quotedMapEntry(int indentLevel, String key, String value) {
        return line(indentLevel, quote(key) + ": " + quote(value));
    }

    public YamlWriter quotedList(int indentLevel, String key, List<String> values) {
        StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                list.append(", ");
            }
            list.append(quote(values.get(i)));
        }
        list.append(']');
        return keyValueRaw(indentLevel, key, list.toString());
    }

    public static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
