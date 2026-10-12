package com.gfi.ozg.fitko.swing.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the submission API publishes about a Zustellpunkt
 * ({@code GET /v2/destinations/{id}}), cached so the compose form can offer
 * the destination's own services, schemas and regions.
 */
public class DestinationInfo {

    public String status;
    public List<String> metadataVersions = new ArrayList<>();
    public List<ServiceInfo> services = new ArrayList<>();
    /** Reply channels the destination allows, e.g. {@code eMail}, {@code deMail}. */
    public List<String> replyChannels = new ArrayList<>();
    public Instant fetchedAt;

    public static class ServiceInfo {
        public String identifier;
        public List<String> regions = new ArrayList<>();
        public List<SchemaInfo> schemas = new ArrayList<>();

        @Override
        public String toString() {
            return identifier;
        }
    }

    public static class SchemaInfo {
        public String schemaUri;
        public String mimeType;

        public DataFormat dataFormat() {
            return mimeType != null && mimeType.toLowerCase().contains("json") ? DataFormat.JSON : DataFormat.XML;
        }

        @Override
        public String toString() {
            return schemaUri;
        }
    }

    public ServiceInfo service(String identifier) {
        return services.stream().filter(s -> s.identifier != null && s.identifier.equals(identifier)).findFirst().orElse(null);
    }

    public boolean allowsReplyChannel(String channel) {
        return replyChannels.stream().anyMatch(c -> c.equalsIgnoreCase(channel));
    }
}
