package com.medicalrag.knowledgebase;

import java.time.Instant;

// IDs are decimal strings on the wire: BIGINT must not lose precision in JavaScript.
public record KnowledgeBase(String id, String name, String description, boolean demo,
        Instant createdAt, Instant updatedAt, long documentCount, long imageCount) { }
