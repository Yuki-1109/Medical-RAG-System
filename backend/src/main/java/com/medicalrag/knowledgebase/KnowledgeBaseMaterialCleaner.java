package com.medicalrag.knowledgebase;

/** A7 can add its real image cleaner here; there is no fake image cleanup in A6. */
public interface KnowledgeBaseMaterialCleaner {
    void markForDeletion(long knowledgeBaseId, long owner);
    void clean(long knowledgeBaseId, long owner);
}
