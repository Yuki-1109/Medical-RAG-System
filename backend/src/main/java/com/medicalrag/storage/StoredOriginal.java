package com.medicalrag.storage;

/** Internal receipt for A6/A7 persistence; never bind it directly from an HTTP request. */
public record StoredOriginal(String storageKey, long size, String sha256, OriginalType type, String originalName) {
    public StoredOriginal {
        if (size <= 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}") || type == null) throw StorageException.unsafe();
        StorageKey key = StorageKey.parse(storageKey);
        if (key.type() != type) throw StorageException.unsafe();
    }
}
