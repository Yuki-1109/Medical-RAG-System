package com.medicalrag.storage;

/** Server-created write intent, persisted before I/O. Never bind this from an HTTP request. */
public record StorageReservation(String storageKey, OriginalType type) {
    public StorageReservation {
        if (type == null || StorageKey.parse(storageKey).type() != type) throw StorageException.unsafe();
    }
}
