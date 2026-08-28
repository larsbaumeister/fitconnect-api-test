package com.gfi.ozg.fitko.spring.config;

import dev.fitko.fitconnect.api.config.defaults.MetadataVersion;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Resolves a version string (e.g. {@code "2.1.0"}) to the matching SDK {@link MetadataVersion} constant. */
public final class MetadataVersions {

    private MetadataVersions() {
    }

    public static MetadataVersion resolve(String rawVersion) {
        return Arrays.stream(MetadataVersion.values())
                .filter(version -> version.getVersion().getVersionAsString().equals(rawVersion))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown metadata version '" + rawVersion
                        + "', supported by this SDK: " + supportedVersions()));
    }

    private static String supportedVersions() {
        return Arrays.stream(MetadataVersion.values())
                .map(version -> version.getVersion().getVersionAsString())
                .collect(Collectors.joining(", "));
    }
}
