package com.gfi.ozg.fitko.sender;

import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import com.gfi.ozg.fitko.common.cli.CliUsageException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Parses a {@code --data-set}/{@code --data-set-file} CLI value of the form
 * {@code schemaUri;mimeType;content} (or {@code schemaUri;mimeType;path} for
 * the file variant) into a FIT-Connect {@link DataSet} - metadata schema
 * v2.x+'s generic, schema-described slot for information FIT-Connect has no
 * dedicated field for. This is where, for example, a citizen's
 * authentication/trust-level proof from their BundID or ELSTER login would
 * travel, using whatever schema you and the receiving side have agreed on
 * out-of-band (FIT-Connect itself never interprets {@code content}, only
 * transports it). A semicolon is used as separator so the spec also works
 * with Windows-style drive letters (e.g. {@code C:\...}) in the file variant.
 */
final class DataSetSpec {

    private final DataSet dataSet;

    private DataSetSpec(DataSet dataSet) {
        this.dataSet = dataSet;
    }

    static DataSetSpec parse(String raw) {
        String[] parts = raw.split(";", 3);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new CliUsageException(
                    "Invalid --data-set value '" + raw + "', expected 'schemaUri;mimeType;content'");
        }
        return new DataSetSpec(buildDataSet(parts[0].trim(), parts[1].trim(), parts[2]));
    }

    static DataSetSpec parseFromFile(String raw) {
        String[] parts = raw.split(";", 3);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new CliUsageException(
                    "Invalid --data-set-file value '" + raw + "', expected 'schemaUri;mimeType;path'");
        }
        Path path = Path.of(parts[2].trim());
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read --data-set-file content '" + path + "'", e);
        }
        return new DataSetSpec(buildDataSet(parts[0].trim(), parts[1].trim(), content));
    }

    private static DataSet buildDataSet(String schemaUri, String mimeType, String content) {
        return new DataSet(
                UUID.randomUUID(),
                new DataSet.DataSetSchema(schemaUri, mimeType),
                new DataSet.DataSetHash("sha512", sha512Hex(content)),
                null,
                content);
    }

    /**
     * FIT-Connect's metadata schema requires every dataSet to carry a
     * sha512 hash (hex-encoded) of its own {@code content}, to protect
     * against tampering in transit; the SDK does not compute this for us
     * (unlike the main submission data), so this sample does it here.
     */
    private static String sha512Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 not available", e);
        }
    }

    DataSet toDataSet() {
        return dataSet;
    }
}
