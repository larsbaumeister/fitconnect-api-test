package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

class DataSetToSendTest {

    @Test
    void computesSha512HashOfContent() throws NoSuchAlgorithmException {
        String content = "{\"levelOfAssurance\":\"http://bsi.bund.de/eID/LoA/hoch\"}";

        DataSet dataSet = DataSetToSend.of("https://example.org/schema.json", "application/json", content).toDataSet();

        assertThat(dataSet.getHash().getType()).isEqualTo("sha512");
        assertThat(dataSet.getHash().getContent()).isEqualTo(sha512Hex(content));
        assertThat(dataSet.getHash().getContent()).matches("^[a-f0-9]{128}$");
    }

    @Test
    void carriesSchemaMimeTypeAndContentThrough() {
        DataSet dataSet = DataSetToSend.of("https://example.org/schema.json", "application/json", "{}").toDataSet();

        assertThat(dataSet.getSchema().getSchemaUri()).isEqualTo("https://example.org/schema.json");
        assertThat(dataSet.getSchema().getMimeType()).isEqualTo("application/json");
        assertThat(dataSet.getContent()).isEqualTo("{}");
        assertThat(dataSet.getDataSetId()).isNotNull();
    }

    @Test
    void assignsADifferentIdToEachDataSet() {
        DataSet first = DataSetToSend.of("https://example.org/schema.json", "application/json", "{}").toDataSet();
        DataSet second = DataSetToSend.of("https://example.org/schema.json", "application/json", "{}").toDataSet();

        assertThat(first.getDataSetId()).isNotEqualTo(second.getDataSetId());
    }

    private static String sha512Hex(String content) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-512").digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
