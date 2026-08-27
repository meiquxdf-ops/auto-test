package com.atest.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared mapper for wire frames and JSON columns. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json serialize failed: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static JsonNode readOrThrow(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid json: " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return MAPPER.convertValue(node, type);
    }

    public static JsonNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** First non-null field among the given aliases. */
    public static JsonNode first(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    public static String text(JsonNode node, String... names) {
        JsonNode v = first(node, names);
        return v == null ? null : (v.isTextual() ? v.asText() : v.toString());
    }
}
