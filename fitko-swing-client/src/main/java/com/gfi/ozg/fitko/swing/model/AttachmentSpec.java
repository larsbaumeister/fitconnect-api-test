package com.gfi.ozg.fitko.swing.model;

/** An attachment to send: a file on disk plus its MIME type. */
public class AttachmentSpec {

    public String path;
    public String mimeType;

    public AttachmentSpec() {
    }

    public AttachmentSpec(String path, String mimeType) {
        this.path = path;
        this.mimeType = mimeType;
    }

    public AttachmentSpec copy() {
        return new AttachmentSpec(path, mimeType);
    }

    @Override
    public String toString() {
        return path + " [" + mimeType + "]";
    }
}
