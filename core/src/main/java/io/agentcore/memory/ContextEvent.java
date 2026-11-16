package io.agentcore.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An immutable native event. Source conversion defaults to the deployed message envelope. */
public final class ContextEvent {
  private static final ObjectMapper CANONICAL =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final String type;
  private final long timestamp;
  private final Map<String, Object> data;

  private ContextEvent(String type, long timestamp, Map<String, Object> data) {
    this.type = Json.text(type, "type");
    if (timestamp < 0 || timestamp > 9007199254740991L)
      throw new IllegalArgumentException("Invalid timestamp");
    this.timestamp = timestamp;
    this.data = immutableObject(data);
    validate(type, this.data);
    if (Json.write(toMap()).getBytes(StandardCharsets.UTF_8).length > 900000)
      throw new IllegalArgumentException("Event is too large");
  }

  public static ContextEvent message(
      long timestamp, String role, List<Map<String, Object>> content, String callId) {
    var data = new LinkedHashMap<String, Object>();
    data.put("role", role);
    data.put("content", content);
    if (callId != null) data.put("callId", Json.text(callId, "callId"));
    return new ContextEvent("message", timestamp, data);
  }

  /** Explicit opt-in for deployments supporting the five typed source event variants. */
  public static ContextEvent typed(String type, long timestamp, Map<String, Object> data) {
    return new ContextEvent(type, timestamp, data);
  }

  public static ContextEvent fromSource(String type, long timestamp, Map<String, Object> data) {
    var source = typed(type, timestamp, data);
    if (type.equals("message")) {
      var converted = new LinkedHashMap<>(source.data);
      var blocks = new ArrayList<Map<String, Object>>();
      for (var value : (List<?>) source.data.get("content")) {
        var block = new LinkedHashMap<>(Json.object(value));
        if ("opaque".equals(block.get("type"))) {
          block.put("format", Json.text(block.get("format"), "opaque.format") + ";encoding=json");
          block.put("value", canonical(block.get("value")));
        }
        blocks.add(block);
      }
      converted.put("content", blocks);
      return new ContextEvent("message", timestamp, converted);
    }
    return message(
        timestamp,
        type.equals("tool_result") ? "tool" : "assistant",
        List.of(
            Map.of(
                "type",
                "opaque",
                "format",
                "harness." + type + ";encoding=json",
                "value",
                canonical(source.data))),
        type.equals("tool_result") ? (String) source.data.get("callId") : null);
  }

  public String type() {
    return type;
  }

  public long timestamp() {
    return timestamp;
  }

  public Map<String, Object> data() {
    return data;
  }

  public Map<String, Object> toMap() {
    return Map.of("type", type, "timestamp", timestamp, "data", data);
  }

  private static void validate(String type, Map<String, Object> data) {
    boolean valid =
        switch (type) {
          case "message" ->
              List.of("user", "assistant", "system", "developer", "tool").contains(data.get("role"))
                  && data.get("content") instanceof List<?>
                  && (!"tool".equals(data.get("role")) || nonblank(data.get("callId")));
          case "tool_call" ->
              nonblank(data.get("callId"))
                  && nonblank(data.get("name"))
                  && data.containsKey("arguments");
          case "tool_result" ->
              nonblank(data.get("callId"))
                  && status(data.get("status"))
                  && data.get("content") instanceof List<?>;
          case "model_request" -> data.get("input") instanceof List<?>;
          case "model_response" -> status(data.get("status"));
          default -> false;
        };
    if (!valid) throw new IllegalArgumentException("Invalid source event");
    if ((type.equals("message") || type.equals("tool_result"))
        && data.get("content") instanceof List<?> blocks) {
      for (var value : blocks) {
        var block = Json.object(value);
        Json.text(block.get("type"), "content.type");
        if ("opaque".equals(block.get("type"))) Json.text(block.get("format"), "opaque.format");
      }
    }
  }

  private static boolean nonblank(Object value) {
    return value instanceof String s && !s.isBlank();
  }

  private static boolean status(Object value) {
    return value instanceof String s && List.of("succeeded", "failed", "cancelled").contains(s);
  }

  private static String canonical(Object value) {
    try {
      return CANONICAL.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid event JSON", error);
    }
  }

  static Map<String, Object> immutableObject(Map<String, Object> value) {
    var result = new TreeMap<String, Object>();
    java.util.Objects.requireNonNull(value)
        .forEach((key, item) -> result.put(java.util.Objects.requireNonNull(key), immutable(item)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutable(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean) return value;
    if (value instanceof Number number) {
      if (!(value instanceof Byte
              || value instanceof Short
              || value instanceof Integer
              || value instanceof Long
              || value instanceof java.math.BigInteger
              || value instanceof java.math.BigDecimal
              || value instanceof Float
              || value instanceof Double)
          || !Double.isFinite(number.doubleValue()))
        throw new IllegalArgumentException("Invalid JSON number");
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String s))
              throw new IllegalArgumentException("JSON keys must be strings");
            result.put(s, immutable(item));
          });
      return Collections.unmodifiableMap(new TreeMap<>(result));
    }
    if (value instanceof List<?> list)
      return Collections.unmodifiableList(list.stream().map(ContextEvent::immutable).toList());
    throw new IllegalArgumentException("Unsupported JSON value");
  }
}
