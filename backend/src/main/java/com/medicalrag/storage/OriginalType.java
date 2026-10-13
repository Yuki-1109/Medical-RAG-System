package com.medicalrag.storage;

public enum OriginalType {
    PDF("pdf", "application/pdf", "documents"), JPEG("jpg", "image/jpeg", "images"), PNG("png", "image/png", "images");
    private final String extension, mediaType, namespace;
    OriginalType(String extension, String mediaType, String namespace) {
        this.extension = extension; this.mediaType = mediaType; this.namespace = namespace;
    }
    public String extension() { return extension; }
    public String mediaType() { return mediaType; }
    public String namespace() { return namespace; }
}
