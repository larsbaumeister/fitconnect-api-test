package com.gfi.ozg.fitko.spring.it;

import com.gfi.ozg.fitko.spring.it.support.AbstractRoundTripIT;
import com.gfi.ozg.fitko.spring.it.support.ITCredentials;
import com.gfi.ozg.fitko.spring.it.support.Payloads;
import com.gfi.ozg.fitko.spring.it.support.RecordingListener;
import com.gfi.ozg.fitko.spring.it.support.RecordingListenerConfig;
import com.gfi.ozg.fitko.spring.send.AttachmentToSend;
import com.gfi.ozg.fitko.spring.send.DataFormat;
import com.gfi.ozg.fitko.spring.send.SubmissionToSend;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-2 - attachments survive the encrypt / transport / decrypt round trip:
 * bytes, filename and mime type, for one attachment, several, and a few MB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RecordingListenerConfig.class)
class AttachmentRoundTripIT extends AbstractRoundTripIT {

    @Autowired
    RecordingListener listener;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Test
    void singleAttachmentRoundTrips() {
        String marker = Payloads.newMarker(getClass());
        byte[] content = ("Personalausweis-Kopie " + marker).getBytes(StandardCharsets.UTF_8);
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker)
                .attachment(AttachmentToSend.ofBytes(content, "text/plain", "ausweis.txt"))
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.attachments()).hasSize(1);
        Attachment attachment = received.attachments().get(0);
        assertThat(attachment.getFileName()).isEqualTo("ausweis.txt");
        assertThat(attachment.getMimeType()).isEqualTo("text/plain");
        assertThat(attachment.getDataAsBytes()).isEqualTo(content);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void multipleAttachmentsOfMixedTypesRoundTrip() {
        String marker = Payloads.newMarker(getClass());
        byte[] txt = ("plain " + marker).getBytes(StandardCharsets.UTF_8);
        byte[] json = ("{\"marker\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] bin = Payloads.attachmentOfSize(4096);
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker)
                .attachment(AttachmentToSend.ofBytes(txt, "text/plain", "notes.txt"))
                .attachment(AttachmentToSend.ofBytes(json, "application/json", "extra.json"))
                .attachment(AttachmentToSend.ofBytes(bin, "application/octet-stream", "payload.bin"))
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.attachments()).hasSize(3);
        assertThat(byName(received.attachments(), "notes.txt").getDataAsBytes()).isEqualTo(txt);
        assertThat(byName(received.attachments(), "notes.txt").getMimeType()).isEqualTo("text/plain");
        assertThat(byName(received.attachments(), "extra.json").getDataAsBytes()).isEqualTo(json);
        assertThat(byName(received.attachments(), "extra.json").getMimeType()).isEqualTo("application/json");
        assertThat(byName(received.attachments(), "payload.bin").getDataAsBytes()).isEqualTo(bin);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    @Test
    void aFewMegabyteAttachmentRoundTrips() {
        String marker = Payloads.newMarker(getClass());
        byte[] big = Payloads.attachmentOfSize(3 * 1024 * 1024); // in-memory path, well below the large-attachment threshold
        listener.acceptMarked(marker);

        SentSubmission sent = send(submission(marker)
                .attachment(AttachmentToSend.ofBytes(big, "application/octet-stream", "big.bin"))
                .build());
        RecordingListener.Received received = awaitReceived(listener, sent);

        assertThat(received.attachments()).hasSize(1);
        assertThat(received.attachments().get(0).getDataAsBytes()).isEqualTo(big);
        assertNotRedelivered(listener, sent.getSubmissionId());
    }

    private SubmissionToSend.Builder submission(String marker) {
        return SubmissionToSend.builder(
                        ITCredentials.serviceId(), "fitko-spring IT (attachments)", DataFormat.XML,
                        Payloads.xml(marker), URI.create(ITCredentials.dataSchema()))
                .destinationId(ITCredentials.destinationId());
    }

    private static Attachment byName(List<Attachment> attachments, String fileName) {
        return attachments.stream()
                .filter(a -> fileName.equals(a.getFileName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No attachment named " + fileName + " in " + attachments));
    }
}
