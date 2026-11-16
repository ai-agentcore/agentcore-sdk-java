package io.agentcore.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.memory.ContextMemoryModels.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class ContextMemoryClientTest {
  private HttpServer server;
  private ContextMemoryClient client;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final List<String> purposes = new CopyOnWriteArrayList<>();
  private volatile Map<String, Object> response;
  private volatile int status = 200;
  private final MemoryScope scope = new MemoryScope("user", "team", "agent", "session");

  private record Request(
      String method,
      String path,
      Map<String, String> query,
      Map<String, Object> body,
      String authorization,
      String action) {}

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var form =
              query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          requests.add(
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getRawPath(),
                  query(exchange.getRequestURI().getRawQuery()),
                  form.containsKey("body") ? Json.read(form.get("body")) : Map.of(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("x-acs-action")));
          byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("x-acs-request-id", "req-header");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    client =
        ContextMemoryClient.fromCredentials(
            new ContextMemoryClient.Options(
                "ws test",
                "cn-hangzhou",
                "store",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                purpose -> {
                  purposes.add(purpose);
                  return Mono.just(new AccessKeyCredential("test-ak", "test-sk"));
                }));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void createSignsContextRequestAndPreservesOutOfOrderReceipts() {
    response =
        success(
            Map.of(
                "results",
                List.of(
                    Map.of(
                        "inputIndex",
                        1,
                        "status",
                        "SKIPPED",
                        "code",
                        "Duplicate",
                        "message",
                        "Already seen"),
                    Map.of("inputIndex", 0, "status", "STORED", "eventId", "evt-1"))));
    var operation = client.createEvents(scope, List.of(event(), event()), null);
    assertTrue(purposes.isEmpty());
    var result = operation.block(Duration.ofSeconds(5));
    assertEquals("req-header", result.requestId());
    assertEquals(
        new OtherEventReceipt(1, "SKIPPED", null, "Duplicate", "Already seen"),
        result.results().get(0));
    assertEquals(new StoredEvent(0, "evt-1", null, null), result.results().get(1));
    var sent = requests.get(0);
    assertEquals("POST", sent.method());
    assertEquals("/workspaces/ws%20test/memorystores/store/events", sent.path());
    assertEquals("CreateEvents", sent.action());
    assertTrue(sent.authorization().contains("test-ak"), sent.authorization());
    assertEquals(scope.toMap(), sent.body().get("scope"));
    var expectedEvent =
        Map.of(
            "type",
            "message",
            "timestamp",
            123,
            "data",
            Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "hello"))));
    assertEquals(List.of(expectedEvent, expectedEvent), sent.body().get("events"));
    assertEquals(List.of("context"), purposes);
    assertEquals(1, requests.size());
  }

  @Test
  void listPreservesEnvelopeAndPassesPaginationAndAllScopeDimensions() {
    var envelope =
        Map.<String, Object>of("eventId", "evt-1", "unknownField", Map.of("nested", List.of(1, 2)));
    response =
        Map.of(
            "success",
            true,
            "items",
            List.of(envelope),
            "nextToken",
            "next +/token",
            "maxResults",
            2,
            "totalCount",
            3,
            "requestId",
            "req-list");
    var first =
        client.listEvents(new ListEventsOptions(scope, null, 2), null).block(Duration.ofSeconds(5));
    var returned = first.items().get(0).envelope();
    assertEquals("evt-1", returned.get("eventId"));
    assertEquals(
        List.of(1L, 2L),
        ((List<?>) Json.object(returned.get("unknownField")).get("nested"))
            .stream().map(value -> ((Number) value).longValue()).toList());
    assertEquals(3L, first.totalCount());
    assertEquals(2, first.maxResults());
    assertEquals("req-list", first.requestId());
    assertThrows(
        UnsupportedOperationException.class, () -> first.items().get(0).envelope().put("x", 1));
    response = Map.of("success", true, "items", List.of());
    var last =
        client
            .listEvents(new ListEventsOptions(scope, first.nextToken(), 2), null)
            .block(Duration.ofSeconds(5));
    assertTrue(last.items().isEmpty());
    assertNull(last.nextToken());
    assertEquals("req-header", last.requestId());
    var sent = requests.get(1);
    assertEquals("GET", sent.method());
    assertTrue(sent.body().isEmpty());
    var expected = new LinkedHashMap<String, String>();
    scope.toMap().forEach((key, value) -> expected.put(key, (String) value));
    expected.put("nextToken", "next +/token");
    expected.put("maxResults", "2");
    assertEquals(expected, sent.query());
    assertEquals(List.of("context", "context"), purposes);
  }

  @ParameterizedTest
  @EnumSource(MemoryType.class)
  void searchPreservesEachTypeProvenanceAndFilters(MemoryType type) {
    var memory = memory(type, scope);
    response =
        success(
            Map.of("memories", List.of(Map.of("memory", memory, "score", 0.9, "similarity", 0.8))));
    var result =
        client
            .searchMemories(
                "preference",
                new SearchOptions(
                    scope, List.of(type), 5, Map.of("topic", "travel"), true, 0.4, 0.5),
                null)
            .block(Duration.ofSeconds(5));
    var hit = result.memories().get(0);
    assertEquals(0.9, hit.score());
    assertEquals(0.8, hit.similarity());
    assertEquals(
        new ContextMemory(
            "mem-1",
            new MemoryContent("prefers trains"),
            scope,
            Map.of("topic", "travel"),
            type,
            "extraction",
            true,
            List.of("evt-1"),
            7L,
            "2026-11-16T00:00:00Z",
            "2026-11-16T01:00:00Z"),
        hit.memory());
    assertEquals("req-header", result.requestId());
    var sent = requests.get(0);
    assertEquals("/workspaces/ws%20test/memorystores/store/memories/search", sent.path());
    assertEquals("SearchMemories", sent.action());
    assertEquals(
        Map.of(
            "query",
            "preference",
            "scope",
            scope.toMap(),
            "types",
            List.of(type.value()),
            "topK",
            5,
            "metadata",
            Map.of("topic", "travel"),
            "enableRerank",
            true,
            "minSimilarity",
            0.4,
            "minScore",
            0.5),
        sent.body());
    assertEquals(List.of("context"), purposes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"scope", "type", "score"})
  void invalidSearchResultsAreRejectedWithRequestId(String invalid) {
    var memory =
        memory(
            invalid.equals("type") ? MemoryType.SKILL : MemoryType.SEMANTIC,
            invalid.equals("scope") ? new MemoryScope("other", "team", "agent", "session") : scope);
    response =
        success(
            Map.of(
                "memories",
                List.of(
                    Map.of(
                        "memory",
                        memory,
                        "score",
                        invalid.equals("score") ? "not-a-number" : 0.9,
                        "similarity",
                        0.8))));
    var error =
        assertThrows(
            AgentCoreException.class,
            () ->
                client
                    .searchMemories(
                        "query",
                        new SearchOptions(
                            scope, List.of(MemoryType.SEMANTIC), 5, null, null, null, null),
                        null)
                    .block(Duration.ofSeconds(5)));
    assertEquals("InvalidResponse", error.serviceCode());
    assertEquals("req-header", error.requestId());
    assertFalse(error instanceof CreateEventsOutcomeUnknownException);
    assertEquals(1, requests.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 503})
  void failedWritesDistinguishRejectionFromUnknownOutcomeWithoutRetry(int httpStatus) {
    status = httpStatus;
    response = Map.of("Code", "Denied", "RequestId", "req-error", "Message", "Unavailable");
    var error =
        assertThrows(
            AgentCoreException.class,
            () -> client.createEvents(scope, List.of(event()), null).block(Duration.ofSeconds(5)));
    assertEquals(httpStatus == 503, error instanceof CreateEventsOutcomeUnknownException);
    assertEquals(httpStatus, error.status());
    assertEquals("req-error", error.requestId());
    assertEquals("Denied", error.serviceCode());
    assertEquals(1, requests.size());
    assertEquals(List.of("context"), purposes);
  }

  @Test
  void applicationRejectionIsKnownEvenWithHttp200() {
    response =
        Map.of(
            "success",
            false,
            "httpStatusCode",
            403,
            "requestId",
            "req-rejected",
            "code",
            "NotAllowed",
            "message",
            "Rejected");
    var error =
        assertThrows(
            AgentCoreException.class,
            () -> client.createEvents(scope, List.of(event()), null).block(Duration.ofSeconds(5)));
    assertFalse(error instanceof CreateEventsOutcomeUnknownException);
    assertEquals("NotAllowed", error.serviceCode());
    assertEquals("req-rejected", error.requestId());
    assertEquals(1, requests.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "duplicate", "out-of-range"})
  void malformedReceiptsLeaveWriteOutcomeUnknown(String invalid) {
    var receipts = new ArrayList<Map<String, Object>>();
    receipts.add(Map.of("inputIndex", 0, "status", "STORED", "eventId", "evt-1"));
    if (!invalid.equals("missing"))
      receipts.add(
          Map.of(
              "inputIndex",
              invalid.equals("duplicate") ? 0 : 2,
              "status",
              "STORED",
              "eventId",
              "evt-2"));
    response = success(Map.of("results", receipts));
    var error =
        assertThrows(
            CreateEventsOutcomeUnknownException.class,
            () ->
                client
                    .createEvents(scope, List.of(event(), event()), null)
                    .block(Duration.ofSeconds(5)));
    assertEquals("InvalidResponse", error.serviceCode());
    assertEquals("req-header", error.requestId());
    assertEquals(1, requests.size());
  }

  @Test
  void invalidInputDoesNotObtainCredentialsOrSendRequests() {
    assertThrows(IllegalArgumentException.class, () -> client.createEvents(scope, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.createEvents(scope, java.util.Collections.nCopies(101, event()), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchOptions(null, null, 201, null, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> new ListEventsOptions(null, null, 101));
    assertTrue(purposes.isEmpty());
    assertTrue(requests.isEmpty());
  }

  private static ContextEvent event() {
    return ContextEvent.message(
        123, "user", List.of(Map.of("type", "text", "text", "hello")), null);
  }

  private static Map<String, Object> success(Map<String, Object> data) {
    return Map.of("success", true, "data", data);
  }

  private static Map<String, Object> memory(MemoryType type, MemoryScope scope) {
    var value = new LinkedHashMap<String, Object>();
    value.put("memoryId", "mem-1");
    value.put("content", Map.of("text", "prefers trains"));
    value.put("scope", scope.toMap());
    value.put("type", type.value());
    value.put("source", "extraction");
    value.put("valid", true);
    value.put("metadata", Map.of("topic", "travel"));
    value.put("sourceEventIds", List.of("evt-1"));
    value.put("version", 7);
    value.put("createdAt", "2026-11-16T00:00:00Z");
    value.put("updatedAt", "2026-11-16T01:00:00Z");
    return value;
  }

  private static Map<String, String> query(String raw) {
    var result = new LinkedHashMap<String, String>();
    if (raw != null && !raw.isEmpty())
      for (String part : raw.split("&")) {
        var pair = part.split("=", 2);
        result.put(
            URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
            pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
      }
    return result;
  }
}
