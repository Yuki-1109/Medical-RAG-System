package com.medicalrag.image;
import com.medicalrag.storage.*;
record ImageRecord(ImageInfo info,String storageKey) {
    long id() { return Long.parseLong(info.id()); }
    long baseId() { return Long.parseLong(info.knowledgeBaseId()); }
    OriginalType type() { return OriginalType.valueOf(info.fileFormat()); }
    StorageReservation reservation() { return new StorageReservation(storageKey,type()); }
    StoredOriginal receipt() { return new StoredOriginal(storageKey,info.fileSize(),info.sha256(),type(),info.originalFilename()); }
}
