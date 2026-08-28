package com.gfi.ozg.fitko.common.cli;

/**
 * Thrown when the command line arguments passed to one of the sample apps are
 * missing, malformed, or contradictory. Callers should catch this at the very
 * top of {@code main}, print the message together with the usage text, and
 * exit with a non-zero status instead of letting a stack trace surface.
 */
public final class CliUsageException extends RuntimeException {

    public CliUsageException(String message) {
        super(message);
    }
}
