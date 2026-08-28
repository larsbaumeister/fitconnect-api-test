package com.gfi.ozg.fitko.common.cli;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A minimal, hand-rolled reader for {@code --option value} style command line
 * arguments. No argument-parsing library is used on purpose, so that the
 * sample apps only ever depend on the FIT-Connect SDK itself.
 *
 * <p>Options may be repeated (e.g. {@code --attachment}); repeated values are
 * collected in the order they were given. Boolean switches (options that take
 * no value, e.g. {@code --accept}) must be declared up front via the
 * constructor so the reader knows not to consume a following token as their
 * value.
 */
public final class ArgumentReader {

    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final Set<String> presentFlags = new LinkedHashSet<>();
    private final Set<String> booleanFlagNames;

    public ArgumentReader(String[] args, Set<String> booleanFlagNames) {
        this.booleanFlagNames = booleanFlagNames;
        int i = 0;
        while (i < args.length) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new CliUsageException("Unexpected argument '" + token + "', expected an option starting with '--'");
            }
            String name = token.substring(2);
            if (booleanFlagNames.contains(name)) {
                presentFlags.add(name);
                i++;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new CliUsageException("Missing value for --" + name);
            }
            values.computeIfAbsent(name, key -> new ArrayList<>()).add(args[i + 1]);
            i += 2;
        }
    }

    public boolean isFlagSet(String name) {
        return presentFlags.contains(name);
    }

    public boolean isSet(String name) {
        return values.containsKey(name);
    }

    public Optional<String> get(String name) {
        List<String> list = values.get(name);
        return (list == null || list.isEmpty()) ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    public String require(String name) {
        return get(name).orElseThrow(() -> new CliUsageException("Missing required argument --" + name));
    }

    public List<String> getAll(String name) {
        return values.getOrDefault(name, List.of());
    }

    public Optional<Integer> getInt(String name) {
        return get(name).map(value -> parseInt(name, value));
    }

    public Optional<UUID> getUuid(String name) {
        return get(name).map(value -> parseUuid(name, value));
    }

    public UUID requireUuid(String name) {
        return parseUuid(name, require(name));
    }

    public Optional<URI> getUri(String name) {
        return get(name).map(value -> parseUri(name, value));
    }

    public URI requireUri(String name) {
        return parseUri(name, require(name));
    }

    /**
     * Parses every occurrence of a repeatable {@code --name key=value} option
     * into a {@code {key, value}} pair, preserving order.
     */
    public List<String[]> getKeyValuePairs(String name) {
        List<String[]> result = new ArrayList<>();
        for (String raw : getAll(name)) {
            int idx = raw.indexOf('=');
            if (idx <= 0 || idx == raw.length() - 1) {
                throw new CliUsageException("Invalid value for --" + name + ": '" + raw + "' (expected key=value)");
            }
            result.add(new String[] {raw.substring(0, idx), raw.substring(idx + 1)});
        }
        return result;
    }

    private static int parseInt(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new CliUsageException("Invalid integer value for --" + name + ": '" + value + "'");
        }
    }

    private static UUID parseUuid(String name, String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("Invalid UUID value for --" + name + ": '" + value + "'");
        }
    }

    private static URI parseUri(String name, String value) {
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("Invalid URI value for --" + name + ": '" + value + "'");
        }
    }
}
