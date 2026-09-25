package com.gfi.ozg.ficon.inbox;

import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One decrypted attachment of an {@link InboxSubmission}, content included.
 *
 * <p>Read fully into memory on store ({@link Attachment#getDataAsBytes()}) -
 * fine for this coverage test, but FIT-Connect allows attachments far larger
 * than is sensible for a database column. For real payload sizes, stream
 * {@link Attachment#getDataAsInputStream()} into object storage instead and
 * keep only a reference here.
 */
@Entity
@Table(name = "inbox_attachment")
public class InboxAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private InboxSubmission submission;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "purpose", length = 20)
    private String purpose;

    @Lob
    @Column(name = "content", nullable = false)
    private byte[] content;

    protected InboxAttachment() {
        // for JPA
    }

    static InboxAttachment from(InboxSubmission submission, int sortOrder, Attachment attachment) {
        InboxAttachment stored = new InboxAttachment();
        stored.submission = submission;
        stored.sortOrder = sortOrder;
        stored.attachmentId = attachment.getAttachmentId();
        stored.fileName = attachment.getFileName();
        stored.description = attachment.getDescription();
        stored.mimeType = attachment.getMimeType();
        stored.purpose = attachment.getPurpose() == null ? null : attachment.getPurpose().name();
        stored.content = attachment.getDataAsBytes();
        return stored;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDescription() {
        return description;
    }

    public String getMimeType() {
        return mimeType;
    }

    /** The SDK's {@code Purpose} name ({@code FORM}, {@code ATTACHMENT}, {@code REPORT}), or {@code null}. */
    public String getPurpose() {
        return purpose;
    }

    public byte[] getDataAsBytes() {
        return content;
    }

    public String getDataAsString() {
        return new String(content, StandardCharsets.UTF_8);
    }
}
