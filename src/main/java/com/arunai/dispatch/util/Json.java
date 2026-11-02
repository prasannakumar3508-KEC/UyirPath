package com.arunai.dispatch.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

/** Shared Jackson mapper. Kept small so repositories can persist JSON columns. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private Json() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot encode JSON", ex);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot decode JSON", ex);
        }
    }

    public static <T> List<T> readList(String json, TypeReference<List<T>> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot decode JSON list", ex);
        }
    }
}
