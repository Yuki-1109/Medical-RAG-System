package com.medicalrag.auth;

import java.time.Instant;

public record Administrator(long id, String username, Instant createdAt) { }
