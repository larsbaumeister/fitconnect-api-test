package dev.fitko.fitconnect.samples.sender;

import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.samples.common.cli.CliUsageException;

import java.nio.file.Path;

/**
 * Parses a {@code --attachment} CLI value of the form
 * {@code path;mimeType[;displayName]} into a FIT-Connect {@link Attachment}.
 * A semicolon is used as separator (instead of a colon) so the spec also
 * works with Windows-style drive letters (e.g. {@code C:\...}).
 */
final class AttachmentSpec {

    private final Path path;
    private final String mimeType;
    private final String displayName;

    private AttachmentSpec(Path path, String mimeType, String displayName) {
        this.path = path;
        this.mimeType = mimeType;
        this.displayName = displayName;
    }

    static AttachmentSpec parse(String raw) {
        String[] parts = raw.split(";", -1);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new CliUsageException(
                    "Invalid --attachment value '" + raw + "', expected 'path;mimeType[;displayName]'");
        }
        Path path = Path.of(parts[0].trim());
        String mimeType = parts[1].trim();
        String displayName = (parts.length >= 3 && !parts[2].isBlank()) ? parts[2].trim() : null;
        return new AttachmentSpec(path, mimeType, displayName);
    }

    /**
     * Loads the attachment into memory. This sample only supports in-memory
     * attachments; use {@code Attachment.fromLargeAttachment(...)} instead if
     * you need to send attachments too large to fit into RAM (see the
     * Java-SDK docs section on attachment chunking).
     */
    Attachment toAttachment() {
        return displayName == null
                ? Attachment.fromPath(path, mimeType)
                : Attachment.fromPath(path, mimeType, displayName, displayName);
    }
}
