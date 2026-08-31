package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.UncheckedIOException;

/** One attachment to add to an {@link SubmissionToSend}, held in memory. */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class AttachmentToSend {

    private final byte[] content;
    private final String mimeType;
    private final String displayName;

    /** Reads {@code resource} fully into memory; its filename becomes the attachment's display name. */
    public static AttachmentToSend of(Resource resource, String mimeType) {
        return of(resource, mimeType, resource.getFilename());
    }

    public static AttachmentToSend of(Resource resource, String mimeType, String displayName) {
        Assert.hasText(mimeType, "mimeType must not be blank");
        try {
            return new AttachmentToSend(resource.getContentAsByteArray(), mimeType, displayName);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read attachment content from " + resource, e);
        }
    }

    public static AttachmentToSend ofBytes(byte[] content, String mimeType, String displayName) {
        Assert.notNull(content, "content must not be null");
        Assert.hasText(mimeType, "mimeType must not be blank");
        return new AttachmentToSend(content, mimeType, displayName);
    }

    Attachment toAttachment() {
        return displayName == null
                ? Attachment.fromByteArray(content, mimeType)
                : Attachment.fromByteArray(content, mimeType, displayName, displayName);
    }
}
