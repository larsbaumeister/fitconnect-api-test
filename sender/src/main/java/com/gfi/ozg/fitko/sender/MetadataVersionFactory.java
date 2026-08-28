package com.gfi.ozg.fitko.sender;

import dev.fitko.fitconnect.api.config.defaults.MetadataVersion;
import com.gfi.ozg.fitko.common.cli.CliUsageException;

import java.util.Arrays;

/**
 * Resolves a {@code --metadata-version} value (e.g. {@code "2.1.0"}) to the
 * matching SDK {@link MetadataVersion} constant. Useful when a destination's
 * configured metadata version isn't the latest the SDK negotiates by
 * default, or is newer than this SDK release supports.
 */
final class MetadataVersionFactory {

    private MetadataVersionFactory() {
    }

    static MetadataVersion create(String rawVersion) {
        return Arrays.stream(MetadataVersion.values())
                .filter(version -> version.getVersion().getVersionAsString().equals(rawVersion))
                .findFirst()
                .orElseThrow(() -> new CliUsageException("Unknown --metadata-version '" + rawVersion
                        + "', supported by this SDK: " + supportedVersions()));
    }

    private static String supportedVersions() {
        StringBuilder versions = new StringBuilder();
        for (MetadataVersion version : MetadataVersion.values()) {
            if (versions.length() > 0) {
                versions.append(", ");
            }
            versions.append(version.getVersion().getVersionAsString());
        }
        return versions.toString();
    }
}
