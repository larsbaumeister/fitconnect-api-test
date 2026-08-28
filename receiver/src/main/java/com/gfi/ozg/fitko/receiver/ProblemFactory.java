package com.gfi.ozg.fitko.receiver;

import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import dev.fitko.fitconnect.api.domain.model.event.problems.other.TechnicalError;
import com.gfi.ozg.fitko.common.cli.CliUsageException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps the {@code --reject-problem} CLI value to a concrete {@link Problem}.
 * The SDK ships many more {@code *.api.domain.model.event.problems.*}
 * subtypes (e.g. under {@code attachment}, {@code metadata}, {@code
 * submission}); this sample only wires up the two simplest, no-argument ones
 * and defaults to {@code TechnicalError}. Add more cases here if your
 * Fachverfahren needs to report a more specific rejection reason.
 */
final class ProblemFactory {

    private static final Map<String, Supplier<Problem>> KNOWN_PROBLEMS = Map.of(
            "TechnicalError", TechnicalError::new,
            "DataSchemaViolation", DataSchemaViolation::new);

    private ProblemFactory() {
    }

    static List<Problem> create(String simpleName) {
        Supplier<Problem> factory = KNOWN_PROBLEMS.get(simpleName);
        if (factory == null) {
            throw new CliUsageException(
                    "Unknown --reject-problem '" + simpleName + "', known values: " + KNOWN_PROBLEMS.keySet());
        }
        return List.of(factory.get());
    }
}
