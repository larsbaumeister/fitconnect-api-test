package com.gfi.ozg.fitko.sender;

import com.gfi.ozg.fitko.common.cli.ArgumentReader;
import com.gfi.ozg.fitko.common.cli.CliUsageException;
import com.gfi.ozg.fitko.common.cli.SharedOptionArgs;
import com.gfi.ozg.fitko.common.config.EnvironmentOverrides;
import com.gfi.ozg.fitko.common.config.HttpTimeouts;
import com.gfi.ozg.fitko.common.config.LocalSchemaMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Turns raw {@code String[] args} into a validated {@link SenderOptions}. */
final class SenderOptionsParser {

    static final Set<String> BOOLEAN_FLAGS = SharedOptionArgs.BOOLEAN_FLAGS;

    private SenderOptionsParser() {
    }

    static SenderOptions parse(String[] args) {
        ArgumentReader reader = new ArgumentReader(args, BOOLEAN_FLAGS);

        String clientId = reader.require("client-id");
        String clientSecret = reader.require("client-secret");
        String environment = reader.get("environment").orElse("TEST");
        UUID destinationId = reader.requireUuid("destination-id");
        String serviceId = reader.require("service-id");
        String serviceName = reader.require("service-name");
        String serviceRegion = reader.get("service-region").orElse(null);
        UUID caseId = reader.getUuid("case-id").orElse(null);
        URI dataSchema = reader.requireUri("data-schema");
        String data = resolveData(reader);
        DataFormat dataFormat = DataFormat.parse(reader.get("data-format").orElse("json"));
        ReplyChannelSpec replyChannel = ReplyChannelSpec.parse(reader);
        UUID idBundDeApplicationId = reader.getUuid("id-bund-de-application-id").orElse(null);
        String metadataVersion = reader.get("metadata-version").orElse(null);

        List<AttachmentSpec> attachments = new ArrayList<>();
        for (String raw : reader.getAll("attachment")) {
            attachments.add(AttachmentSpec.parse(raw));
        }

        List<DataSetSpec> dataSets = new ArrayList<>();
        for (String raw : reader.getAll("data-set")) {
            dataSets.add(DataSetSpec.parse(raw));
        }
        for (String raw : reader.getAll("data-set-file")) {
            dataSets.add(DataSetSpec.parseFromFile(raw));
        }

        EnvironmentOverrides overrides = SharedOptionArgs.parseEnvironmentOverrides(reader);
        HttpTimeouts timeouts = SharedOptionArgs.parseHttpTimeouts(reader);
        LocalSchemaMapping localSchemas = SharedOptionArgs.parseLocalSchemas(reader);

        return new SenderOptions(clientId, clientSecret, environment, destinationId, serviceId, serviceName,
                serviceRegion, caseId, data, dataFormat, dataSchema, attachments, replyChannel, idBundDeApplicationId,
                metadataVersion, dataSets, overrides, timeouts, localSchemas);
    }

    private static String resolveData(ArgumentReader reader) {
        boolean hasInline = reader.isSet("data");
        boolean hasFile = reader.isSet("data-file");
        if (hasInline == hasFile) {
            throw new CliUsageException("Specify exactly one of --data or --data-file");
        }
        if (hasInline) {
            return reader.require("data");
        }
        Path path = Path.of(reader.require("data-file"));
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read --data-file '" + path + "'", e);
        }
    }
}
