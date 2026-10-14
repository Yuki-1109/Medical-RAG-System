package com.medicalrag.settings;

import java.time.Instant;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

final class ModelTypes {
    enum Type { CHAT, EMBEDDING }
    record Input(String type, String protocol, String baseUrl, String modelId, String apiKey, boolean deleteKey) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Input parse(JsonNode node) {
            validate(node,Set.of("type","protocol","baseUrl","modelId","apiKey","deleteKey"));
            for (String field:Set.of("type","protocol","baseUrl","modelId")) if (!node.path(field).isTextual()) throw ModelException.invalid();
            if (node.hasNonNull("apiKey") && !node.path("apiKey").isTextual() || node.has("deleteKey") && !node.path("deleteKey").isBoolean()) throw ModelException.invalid();
            return new Input(node.path("type").asText(),node.path("protocol").asText(),node.path("baseUrl").asText(),node.path("modelId").asText(),node.hasNonNull("apiKey")?node.path("apiKey").asText():null,node.path("deleteKey").asBoolean(false));
        }
        @Override public String toString() { return "ModelInput[REDACTED]"; }
    }
    record TestInput(String type) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static TestInput parse(JsonNode node) {
            validate(node,Set.of("type")); if (!node.path("type").isTextual()) throw ModelException.invalid();
            return new TestInput(node.path("type").asText());
        }
    }
    private static void validate(JsonNode node, Set<String> fields) {
        if (node==null || !node.isObject()) throw ModelException.invalid();
        node.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw ModelException.invalid(); });
    }
    record Info(Type type, String protocol, String baseUrl, String modelId, boolean keyConfigured,
                boolean configured, String testStatus, String testCode, String testMessage, Instant testedAt,
                Integer embeddingDimension, long revision) { }
    record Stored(long owner, Type type, String protocol, String baseUrl, String modelId, String cipher,
                  long revision, String testStatus, String testCode, Instant testedAt, Integer dimension) {
        @Override public String toString() { return "ModelConfig[REDACTED]"; }
    }
    static Type type(String value) {
        try { return Type.valueOf(value); } catch (RuntimeException e) { throw ModelException.invalid(); }
    }
}
