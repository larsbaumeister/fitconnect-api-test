package com.gfi.ozg.fitko.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional {@code submissionDataSchemas} mapping of schema URI/URN to a local
 * file path, used to validate submission/reply data against a locally cached
 * schema instead of fetching it over HTTP on every call.
 */
public final class LocalSchemaMapping {

    private final Map<String, String> mapping = new LinkedHashMap<>();

    public void add(String schemaUri, String localFilePath) {
        mapping.put(schemaUri, localFilePath);
    }

    public boolean isEmpty() {
        return mapping.isEmpty();
    }

    public void writeTo(YamlWriter yaml) {
        if (isEmpty()) {
            return;
        }
        yaml.line(0, "submissionDataSchemas:");
        mapping.forEach((uri, path) -> yaml.quotedMapEntry(1, uri, path));
    }
}
