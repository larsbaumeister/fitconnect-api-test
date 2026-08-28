package com.gfi.ozg.fitko.receiver;

import com.gfi.ozg.fitko.common.config.EnvironmentOverrides;
import com.gfi.ozg.fitko.common.config.HttpTimeouts;
import com.gfi.ozg.fitko.common.config.LocalSchemaMapping;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Immutable holder for the fully-parsed and validated receiver CLI options. */
final class ReceiverOptions {

    private final String clientId;
    private final String clientSecret;
    private final String environment;
    private final String signingKeyPath;
    private final List<String> decryptionKeyPaths;
    private final UUID destinationId;
    private final UUID submissionId;
    private final int offset;
    private final int limit;
    private final Path outputDir;
    private final boolean accept;
    private final boolean reject;
    private final String rejectProblem;
    private final EnvironmentOverrides environmentOverrides;
    private final HttpTimeouts httpTimeouts;
    private final LocalSchemaMapping localSchemas;

    ReceiverOptions(String clientId, String clientSecret, String environment, String signingKeyPath,
                     List<String> decryptionKeyPaths, UUID destinationId, UUID submissionId, int offset, int limit,
                     Path outputDir, boolean accept, boolean reject, String rejectProblem,
                     EnvironmentOverrides environmentOverrides, HttpTimeouts httpTimeouts,
                     LocalSchemaMapping localSchemas) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.environment = environment;
        this.signingKeyPath = signingKeyPath;
        this.decryptionKeyPaths = decryptionKeyPaths;
        this.destinationId = destinationId;
        this.submissionId = submissionId;
        this.offset = offset;
        this.limit = limit;
        this.outputDir = outputDir;
        this.accept = accept;
        this.reject = reject;
        this.rejectProblem = rejectProblem;
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

    String getSigningKeyPath() {
        return signingKeyPath;
    }

    List<String> getDecryptionKeyPaths() {
        return decryptionKeyPaths;
    }

    UUID getDestinationId() {
        return destinationId;
    }

    UUID getSubmissionId() {
        return submissionId;
    }

    int getOffset() {
        return offset;
    }

    int getLimit() {
        return limit;
    }

    Path getOutputDir() {
        return outputDir;
    }

    boolean isAccept() {
        return accept;
    }

    boolean isReject() {
        return reject;
    }

    String getRejectProblem() {
        return rejectProblem;
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
