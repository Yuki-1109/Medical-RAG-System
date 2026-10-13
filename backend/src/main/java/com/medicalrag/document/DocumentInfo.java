package com.medicalrag.document;

import java.time.Instant;

/** No disk path, storage key or private account information on the wire. */
public record DocumentInfo(String id, String knowledgeBaseId, String originalFilename, Long fileSize,
        String sha256, Integer pageCount, String processingStatus, String errorReason,
        String lifecycleStatus, String cleanupError, Instant createdAt, Instant updatedAt) { }
