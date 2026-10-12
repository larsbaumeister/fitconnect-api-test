package com.gfi.ozg.fitko.swing.store;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * One JSON file holding one value. Saves are atomic (temp file + move), so a
 * crash mid-write never leaves a half-written file. A file that can't be read
 * is moved aside to {@code <name>.corrupt-<timestamp>} and {@link #load}
 * returns the empty value instead of failing the whole app.
 */
public class JsonFileStore<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final TypeReference<T> type;
    private final Supplier<T> empty;
    private final boolean ownerOnly;
    private String loadWarning;

    public JsonFileStore(Path file, TypeReference<T> type, Supplier<T> empty, boolean ownerOnly) {
        this.file = file;
        this.type = type;
        this.empty = empty;
        this.ownerOnly = ownerOnly;
    }

    public Path file() {
        return file;
    }

    /** Set if the last {@link #load} found a corrupt file and moved it aside. */
    public String loadWarning() {
        return loadWarning;
    }

    public T load() {
        loadWarning = null;
        if (!Files.exists(file)) {
            return empty.get();
        }
        try {
            T value = Json.MAPPER.readValue(file.toFile(), type);
            return value == null ? empty.get() : value;
        } catch (IOException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
            log.warn("Could not read {}, moving it to {}", file, backup, e);
            try {
                Files.move(file, backup);
                loadWarning = file.getFileName() + " could not be read and was moved to " + backup.getFileName()
                        + ": " + e.getMessage();
            } catch (IOException moveFailed) {
                loadWarning = file.getFileName() + " could not be read: " + e.getMessage();
            }
            return empty.get();
        }
    }

    public void save(T value) {
        write(serialize(value));
    }

    /** Serialization half of {@link #save}, so it can run on the caller's thread and {@link #write} elsewhere. */
    public byte[] serialize(T value) {
        try {
            return Json.MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize " + file.getFileName(), e);
        }
    }

    public void write(byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                restrictToOwner(tmp);
                Files.write(tmp, content);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    private void restrictToOwner(Path path) {
        if (!ownerOnly) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system (Windows): the user profile is private anyway.
        }
    }
}
