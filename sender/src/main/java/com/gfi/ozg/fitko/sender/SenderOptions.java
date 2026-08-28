package com.gfi.ozg.fitko.sender;

import com.gfi.ozg.fitko.common.config.EnvironmentOverrides;
import com.gfi.ozg.fitko.common.config.HttpTimeouts;
import com.gfi.ozg.fitko.common.config.LocalSchemaMapping;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Immutable holder for the fully-parsed and validated sender CLI options. */
final class SenderOptions {

    private final String clientId;
    private final String clientSecret;
    private final String environment;
    private final UUID destinationId;
    private final String serviceId;
    private final String serviceName;
    private final String serviceRegion;
    private final UUID caseId;
    private final String data;
    private final DataFormat dataFormat;
    private final URI dataSchema;
    private final List<AttachmentSpec> attachments;
    private final ReplyChannelSpec replyChannel;
    private final UUID idBundDeApplicationId;
    private final String metadataVersion;
    private final List<DataSetSpec> dataSets;
    private final EnvironmentOverrides environmentOverrides;
    private final HttpTimeouts httpTimeouts;
    private final LocalSchemaMapping localSchemas;

    SenderOptions(String clientId, String clientSecret, String environment, UUID destinationId, String serviceId,
                  String serviceName, String serviceRegion, UUID caseId, String data, DataFormat dataFormat, URI dataSchema,
                  List<AttachmentSpec> attachments, ReplyChannelSpec replyChannel, UUID idBundDeApplicationId,
                  String metadataVersion, List<DataSetSpec> dataSets, EnvironmentOverrides environmentOverrides,
                  HttpTimeouts httpTimeouts, LocalSchemaMapping localSchemas) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.environment = environment;
        this.destinationId = destinationId;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.serviceRegion = serviceRegion;
        this.caseId = caseId;
        this.data = data;
        this.dataFormat = dataFormat;
        this.dataSchema = dataSchema;
        this.attachments = attachments;
        this.replyChannel = replyChannel;
        this.idBundDeApplicationId = idBundDeApplicationId;
        this.metadataVersion = metadataVersion;
        this.dataSets = dataSets;
        this.environmentOverrides = environmentOverrides;
        this.httpTimeouts = httpTimeouts;
        this.localSchemas = localSchemas;
    }

    String getClientId() {
        return clientId;
    }

    String getClientSecret() {
        return clientSecret;
    }

    String getEnvironment() {
        return environment;
    }

    UUID getDestinationId() {
        return destinationId;
    }

    String getServiceId() {
        return serviceId;
    }

    String getServiceName() {
        return serviceName;
    }

    String getServiceRegion() {
        return serviceRegion;
    }

    UUID getCaseId() {
        return caseId;
    }

    String getData() {
        return data;
    }

    DataFormat getDataFormat() {
        return dataFormat;
    }

    URI getDataSchema() {
        return dataSchema;
    }

    List<AttachmentSpec> getAttachments() {
        return attachments;
    }

    ReplyChannelSpec getReplyChannel() {
        return replyChannel;
    }

    UUID getIdBundDeApplicationId() {
        return idBundDeApplicationId;
    }

    String getMetadataVersion() {
        return metadataVersion;
    }

    List<DataSetSpec> getDataSets() {
        return dataSets;
    }

    EnvironmentOverrides getEnvironmentOverrides() {
        return environmentOverrides;
    }

    HttpTimeouts getHttpTimeouts() {
        return httpTimeouts;
    }

    LocalSchemaMapping getLocalSchemas() {
        return localSchemas;
    }
}
