package com.gfi.ozg.fitko.sender;

import com.gfi.ozg.fitko.common.cli.CliUsageException;

/** The wire format of {@code --data}/{@code --data-file}, selected via {@code --data-format}. */
enum DataFormat {
    JSON,
    XML;

    static DataFormat parse(String raw) {
        try {
            return DataFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("Unknown --data-format '" + raw + "', expected 'json' or 'xml'");
        }
    }
}
