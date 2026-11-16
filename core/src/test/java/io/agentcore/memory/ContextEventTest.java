package io.agentcore.memory;

import static org.junit.jupiter.api.Assertions.*;

import io.agentcore.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextEventTest {
  @Test
  void eventOwnsAnImmutableDeepSnapshot() {
    var block = new LinkedHashMap<String, Object>(Map.of("type", "text", "text", "original"));
    var content = new ArrayList<Map<String, Object>>(List.of(block));
    var event = ContextEvent.message(123, "user", content, null);
    block.put("text", "modified");
    content.clear();
    var retained = (List<?>) event.data().get("content");
    assertEquals(List.of(Map.of("type", "text", "text", "original")), retained);
    assertThrows(UnsupportedOperationException.class, () -> event.data().put("role", "assistant"));
    assertThrows(UnsupportedOperationException.class, retained::clear);
    assertThrows(
        UnsupportedOperationException.class,
        () -> Json.object(retained.get(0)).put("text", "changed"));
  }

  @Test
  void opaqueMessageBlocksUseCanonicalJsonWithoutMutatingTheSource() {
    var value = new LinkedHashMap<String, Object>();
    value.put("z", 2);
    value.put("a", Map.of("nested", true));
    var block = Map.<String, Object>of("type", "opaque", "format", "vendor", "value", value);
    var source = Map.<String, Object>of("role", "assistant", "content", List.of(block));
    var event = ContextEvent.fromSource("message", 123, source);
    var converted = Json.object(((List<?>) event.data().get("content")).get(0));
    assertEquals("vendor;encoding=json", converted.get("format"));
    assertEquals("{\"a\":{\"nested\":true},\"z\":2}", converted.get("value"));
    assertEquals("vendor", block.get("format"));
    assertSame(value, block.get("value"));
  }

  @Test
  void invalidEventsAreRejectedBeforeSerializationOrDispatch() {
    assertThrows(
        IllegalArgumentException.class, () -> ContextEvent.message(-1, "user", List.of(), null));
    assertThrows(
        IllegalArgumentException.class, () -> ContextEvent.message(1, "unknown", List.of(), null));
    assertThrows(
        IllegalArgumentException.class, () -> ContextEvent.message(1, "tool", List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContextEvent.typed(
                "tool_call",
                1,
                Map.of("callId", "call", "name", "lookup", "arguments", Double.NaN)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContextEvent.typed("model_response", 1, Map.of("status", "unknown")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContextEvent.message(
                1, "user", List.of(Map.of("type", "text", "text", "x".repeat(900001))), null));
  }
}
