package com.gfi.ozg.fitko.spring.send;

import lombok.Getter;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An Antrag to hand to {@link AntragSender#send}. Immutable; build one with
 * {@link #builder(String, String, DataFormat, String, URI)}.
 *
 * <pre>{@code
 * AntragToSend antrag = AntragToSend.builder(
 *         "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
 *         DataFormat.XML, xmlPayload, dataSchemaUri)
 *     .destinationId(destinationId)
 *     .caseId(existingCaseId)
 *     .replyChannelEmail("applicant@example.com")
 *     .attachment(AttachmentToSend.of(pdfResource, "application/pdf"))
 *     .build();
 * SentSubmission sent = antragSender.send(antrag);
 * }</pre>
 */
@Getter
public final class AntragToSend {

    private final UUID destinationId;
    private final String serviceId;
    private final String serviceName;
    private final String serviceRegion;
    private final DataFormat dataFormat;
    private final String data;
    private final URI dataSchema;
    private final UUID caseId;
    private final String replyChannelEmail;
    private final List<AttachmentToSend> attachments;
    private final List<DataSetToSend> dataSets;
    private final String metadataVersion;

    private AntragToSend(Builder builder) {
        this.destinationId = builder.destinationId;
        this.serviceId = builder.serviceId;
        this.serviceName = builder.serviceName;
        this.serviceRegion = builder.serviceRegion;
        this.dataFormat = builder.dataFormat;
        this.data = builder.data;
        this.dataSchema = builder.dataSchema;
        this.caseId = builder.caseId;
        this.replyChannelEmail = builder.replyChannelEmail;
        this.attachments = List.copyOf(builder.attachments);
        this.dataSets = List.copyOf(builder.dataSets);
        this.metadataVersion = builder.metadataVersion;
    }

    /**
     * @param serviceId   LeiKa key of the Leistung, e.g. {@code urn:de:fim:leika:leistung:...}
     * @param serviceName human-readable service name
     * @param dataFormat  format of {@code data}
     * @param data        the main submission payload
     * @param dataSchema  schema {@code data} conforms to
     */
    public static Builder builder(String serviceId, String serviceName, DataFormat dataFormat, String data, URI dataSchema) {
        return new Builder(serviceId, serviceName, dataFormat, data, dataSchema);
    }

    public static final class Builder {

        private final String serviceId;
        private final String serviceName;
        private final DataFormat dataFormat;
        private final String data;
        private final URI dataSchema;

        private UUID destinationId;
        private String serviceRegion;
        private UUID caseId;
        private String replyChannelEmail;
        private final List<AttachmentToSend> attachments = new ArrayList<>();
        private final List<DataSetToSend> dataSets = new ArrayList<>();
        private String metadataVersion;

        private Builder(String serviceId, String serviceName, DataFormat dataFormat, String data, URI dataSchema) {
            Assert.hasText(serviceId, "serviceId must not be blank");
            Assert.hasText(serviceName, "serviceName must not be blank");
            Assert.notNull(dataFormat, "dataFormat must not be null");
            Assert.hasText(data, "data must not be blank");
            Assert.notNull(dataSchema, "dataSchema must not be null");
            this.serviceId = serviceId;
            this.serviceName = serviceName;
            this.dataFormat = dataFormat;
            this.data = data;
            this.dataSchema = dataSchema;
        }

        /** Required: the Zustellpunkt (destination) this Antrag is sent to. */
        public Builder destinationId(UUID destinationId) {
            this.destinationId = destinationId;
            return this;
        }

        public Builder serviceRegion(String serviceRegion) {
            this.serviceRegion = serviceRegion;
            return this;
        }

        /** Appends this Antrag to an existing case instead of starting a new one. */
        public Builder caseId(UUID caseId) {
            this.caseId = caseId;
            return this;
        }

        /** Asks the receiver to reply by e-mail. At most one reply channel may be set. */
        public Builder replyChannelEmail(String replyChannelEmail) {
            this.replyChannelEmail = replyChannelEmail;
            return this;
        }

        public Builder attachment(AttachmentToSend attachment) {
            this.attachments.add(attachment);
            return this;
        }

        public Builder attachments(List<AttachmentToSend> attachments) {
            this.attachments.addAll(attachments);
            return this;
        }

        public Builder dataSet(DataSetToSend dataSet) {
            this.dataSets.add(dataSet);
            return this;
        }

        public Builder dataSets(List<DataSetToSend> dataSets) {
            this.dataSets.addAll(dataSets);
            return this;
        }

        /**
         * Forces a metadata schema version (e.g. {@code "2.1.0"}) instead of
         * auto-negotiating with the destination. Only works if the
         * destination is also configured to accept that version.
         */
        public Builder metadataVersion(String metadataVersion) {
            this.metadataVersion = metadataVersion;
            return this;
        }

        public AntragToSend build() {
            return new AntragToSend(this);
        }
    }

    @Override
    public String toString() {
        return "AntragToSend{destinationId=" + destinationId + ", serviceId='" + serviceId + '\''
                + ", caseId=" + caseId + '}';
    }
}
