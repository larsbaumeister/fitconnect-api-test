package dev.fitko.fitconnect.samples.receiver;

import dev.fitko.fitconnect.samples.common.cli.ArgumentReader;
import dev.fitko.fitconnect.samples.common.cli.CliUsageException;
import dev.fitko.fitconnect.samples.common.cli.SharedOptionArgs;
import dev.fitko.fitconnect.samples.common.config.EnvironmentOverrides;
import dev.fitko.fitconnect.samples.common.config.HttpTimeouts;
import dev.fitko.fitconnect.samples.common.config.LocalSchemaMapping;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Turns raw {@code String[] args} into a validated {@link ReceiverOptions}. */
final class ReceiverOptionsParser {

    static final Set<String> BOOLEAN_FLAGS = booleanFlags();

    private ReceiverOptionsParser() {
    }

    private static Set<String> booleanFlags() {
        Set<String> flags = new LinkedHashSet<>(SharedOptionArgs.BOOLEAN_FLAGS);
        flags.add("accept");
        flags.add("reject");
        return flags;
    }

    static ReceiverOptions parse(String[] args) {
        ArgumentReader reader = new ArgumentReader(args, BOOLEAN_FLAGS);

        String clientId = reader.require("client-id");
        String clientSecret = reader.require("client-secret");
        String environment = reader.get("environment").orElse("TEST");
        String signingKeyPath = reader.require("signing-key");
        List<String> decryptionKeyPaths = reader.getAll("decryption-key");
        if (decryptionKeyPaths.isEmpty()) {
            throw new CliUsageException("At least one --decryption-key is required");
        }
        UUID destinationId = reader.requireUuid("destination-id");
        UUID submissionId = reader.getUuid("submission-id").orElse(null);
        int offset = reader.getInt("offset").orElse(0);
        int limit = reader.getInt("limit").orElse(100);
        Path outputDir = Path.of(reader.get("output-dir").orElse("./fitconnect-received"));

        boolean accept = reader.isFlagSet("accept");
        boolean reject = reader.isFlagSet("reject");
        if (accept && reject) {
            throw new CliUsageException("Specify at most one of --accept or --reject");
        }
        String rejectProblem = reader.get("reject-problem").orElse("TechnicalError");

        EnvironmentOverrides overrides = SharedOptionArgs.parseEnvironmentOverrides(reader);
        HttpTimeouts timeouts = SharedOptionArgs.parseHttpTimeouts(reader);
        LocalSchemaMapping localSchemas = SharedOptionArgs.parseLocalSchemas(reader);

        return new ReceiverOptions(clientId, clientSecret, environment, signingKeyPath, decryptionKeyPaths,
                destinationId, submissionId, offset, limit, outputDir, accept, reject, rejectProblem, overrides,
                timeouts, localSchemas);
    }
}
