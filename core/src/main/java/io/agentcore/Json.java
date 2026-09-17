package io.agentcore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** JSON support shared by resource transports and adapters. */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot serialize JSON", e); }
    }
    public static Map<String, Object> read(String value) {
        try { return MAPPER.readValue(value, OBJECT); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON object"); }
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected an object");
        return (Map<String, Object>) value;
    }
    public static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(field + " must be a non-empty string");
        return text;
    }
}
