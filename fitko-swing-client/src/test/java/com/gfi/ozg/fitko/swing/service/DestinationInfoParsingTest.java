package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.DataFormat;
import com.gfi.ozg.fitko.swing.model.DestinationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestinationInfoParsingTest {

    /** Shape of {@code GET /v2/destinations/{id}} on the submission API (see the SDK's PublicDestination). */
    private static final String BODY = """
            {
              "destinationId": "9f6bb611-df46-494a-9a98-a253f1362dc7",
              "status": "active",
              "encryptionKid": "kid-1",
              "metadataVersions": ["1.4.0", "2.0.0"],
              "replyChannels": { "eMail": { "usePgp": false }, "deMail": null },
              "publicServices": [
                {
                  "identifier": "urn:de:fim:leika:leistung:99050035001000",
                  "regions": ["DE09"],
                  "submissionSchemas": [
                    { "schemaUri": "https://fimportal.de/api/v0/leistung-steckbriefe/99050035001000/xzufi", "mimeType": "application/xml" },
                    { "schemaUri": "https://schema.example/x.schema.json", "mimeType": "application/json" }
                  ]
                },
                { "identifier": "urn:de:fim:leika:leistung:99000000000000" }
              ],
              "somethingNew": true
            }
            """;

    @Test
    void parsesServicesSchemasRegionsAndChannels() throws Exception {
        DestinationInfo info = DestinationInfoService.parse(BODY);

        assertEquals("active", info.status);
        assertEquals(List.of("1.4.0", "2.0.0"), info.metadataVersions);
        assertEquals(List.of("eMail"), info.replyChannels);
        assertTrue(info.allowsReplyChannel("email"));
        assertFalse(info.allowsReplyChannel("deMail"));
        assertNotNull(info.fetchedAt);

        assertEquals(2, info.services.size());
        DestinationInfo.ServiceInfo service = info.service("urn:de:fim:leika:leistung:99050035001000");
        assertEquals(List.of("DE09"), service.regions);
        assertEquals(2, service.schemas.size());
        assertEquals(DataFormat.XML, service.schemas.get(0).dataFormat());
        assertEquals(DataFormat.JSON, service.schemas.get(1).dataFormat());
        assertTrue(info.service("urn:de:fim:leika:leistung:99000000000000").schemas.isEmpty());
    }

    @Test
    void toleratesAnAlmostEmptyBody() throws Exception {
        DestinationInfo info = DestinationInfoService.parse("{}");
        assertTrue(info.services.isEmpty());
        assertTrue(info.replyChannels.isEmpty());
    }
}
