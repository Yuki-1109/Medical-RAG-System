package com.medicalrag.health;

import java.time.Instant;

public record HealthResponse(
        String status,
        Instant checkedAt,
        Component backend,
        Component database,
        Component pgvector,
        Component migrations) {

    public record Component(String status, String message, String version) {
        public static Component up(String message, String version) {
            return new Component("UP", message, version);
        }

        public static Component down(String message) {
            return new Component("DOWN", message, null);
        }

        public static Component unknown(String message) {
            return new Component("UNKNOWN", message, null);
        }
    }
}
