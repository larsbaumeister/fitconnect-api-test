package com.gfi.ozg.fitko.spring.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.DataSetToSend;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.metadata.Metadata;
import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import dev.fitko.fitconnect.api.domain.model.metadata.v2.MetadataV2;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * IT-3 - the metadata v2 {@code dataSets} slot: an {@code IdentificationReport}
 * (authentication / trust-level proof, see
 * notes/submission-identity-routing-trust.md) attached with
 * {@link DataSetToSend} arrives intact, with its content, schema reference and
 * auto-computed sha512 hash; a requested metadata version is honoured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RecordingListenerConfig.class)
class MetadataIdentityRoundTripIT extends AbstractRoundTripIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    RecordingListener listener;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void identificationReportDataSetRoundTripsWithLevelOfAssurance() throws Exception {
        String marker = Payloads.newMarker(getClass());
        String subjectRef = "it-subject-" + marker.hashCode();
        String loa = "http://eidas.europa.eu/LoA/substantial";
        String report = Payloads.identificationReport(subjectRef, loa);
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker)
                .dataSet(DataSetToSend.of(Payloads.identificationReportSchemaUri(), "application/json", report))
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        DataSet dataSet = onlyDataSet(received.metadata());
        assertThat(dataSet.getSchema().getSchemaUri()).isEqualTo(Payloads.identificationReportSchemaUri());
        assertThat(dataSet.getSchema().getMimeType()).isEqualTo("application/json");
        assertThat(dataSet.getHash().getType()).isEqualTo("sha512");
        assertThat(dataSet.getHash().getContent()).isNotBlank();

        JsonNode content = JSON.readTree(dataSet.getContent());
        assertThat(content.at("/levelOfAssurance").asText()).isEqualTo(loa);
        assertThat(content.at("/identificationValues/subjectRef").asText()).isEqualTo(subjectRef);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void aRequestedMetadataVersionIsHonoured() {
        String marker = Payloads.newMarker(getClass());
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker)
                .metadataVersion("2.1.0")
                .dataSet(DataSetToSend.of(Payloads.identificationReportSchemaUri(), "application/json",
                        Payloads.identificationReport("subj-" + marker.hashCode(), "http://eidas.europa.eu/LoA/low")))
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.metadata()).isInstanceOf(MetadataV2.class);
        assertThat(received.metadata().getSchema()).contains("2.");
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void multipleDataSetsAllRoundTrip() {
        String marker = Payloads.newMarker(getClass());
        String reportA = Payloads.identificationReport("subjA-" + marker.hashCode(), "http://eidas.europa.eu/LoA/low");
        String reportB = Payloads.identificationReport("subjB-" + marker.hashCode(), "http://eidas.europa.eu/LoA/high");
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker)
                .dataSet(DataSetToSend.of(Payloads.identificationReportSchemaUri(), "application/json", reportA))
                .dataSet(DataSetToSend.of(Payloads.identificationReportSchemaUri(), "application/json", reportB))
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        List<DataSet> dataSets = dataSets(received.metadata());
        assertThat(dataSets).hasSize(2);
        assertThat(dataSets).extracting(DataSet::getContent).containsExactlyInAnyOrder(reportA, reportB);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    private SubmissionToSend.Builder submission(String marker) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (metadata)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId());
    }

    private static List<DataSet> dataSets(Metadata metadata) {
        if (metadata instanceof MetadataV2 v2 && v2.getDataSets() != null) {
            return v2.getDataSets();
        }
        return fail("Received metadata carried no dataSets: " + metadata);
    }

    private static DataSet onlyDataSet(Metadata metadata) {
        List<DataSet> dataSets = dataSets(metadata);
        assertThat(dataSets).hasSize(1);
        return dataSets.get(0);
    }
}
