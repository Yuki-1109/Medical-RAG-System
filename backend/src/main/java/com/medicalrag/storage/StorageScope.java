package com.medicalrag.storage;

/** Internal capability issued only after authentication and knowledge-base ownership checks. */
final class StorageScope {
    final long knowledgeBaseId;
    StorageScope(long knowledgeBaseId) {
        if (knowledgeBaseId <= 0) throw StorageException.unsafe();
        this.knowledgeBaseId = knowledgeBaseId;
    }
}
