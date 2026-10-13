package com.medicalrag.document;

import com.medicalrag.storage.*;

record DocumentRecord(DocumentInfo info, String storageKey) {
    long id() { return Long.parseLong(info.id()); }
    long baseId() { return Long.parseLong(info.knowledgeBaseId()); }
    StorageReservation reservation() { return new StorageReservation(storageKey, OriginalType.PDF); }
    StoredOriginal receipt() { return new StoredOriginal(storageKey, info.fileSize(), info.sha256(), OriginalType.PDF, info.originalFilename()); }
}
