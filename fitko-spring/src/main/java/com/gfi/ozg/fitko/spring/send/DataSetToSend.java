package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * A metadata schema v2.x+ {@code dataSet}: the generic, schema-described slot
 * FIT-Connect provides for information it has no dedicated field for, e.g. a
 * citizen's authentication/trust-level proof from their BundID or ELSTER
 * login (see the {@code IdentificationReport} schema). FIT-Connect never
 * interprets {@code content}, only transports it, using whatever schema the
 * sender and receiver have agreed on out-of-band.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class DataSetToSend {

    private final DataSet dataSet;

    /**
     * @param schemaUri the JSON/XML schema {@code content} conforms to
     * @param mimeType  {@code content}'s mime type, e.g. {@code application/json}
     * @param content   the dataSet's payload; its sha512 hash is computed automatically,
     *                  as FIT-Connect's metadata schema requires
     */
    public static DataSetToSend of(String schemaUri, String mimeType, String content) {
        return new DataSetToSend(new DataSet(
                UUID.randomUUID(),
                new DataSet.DataSetSchema(schemaUri, mimeType),
                new DataSet.DataSetHash("sha512", sha512Hex(content)),
                null,
                content));
    }

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
