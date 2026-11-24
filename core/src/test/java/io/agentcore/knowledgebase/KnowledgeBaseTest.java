package io.agentcore.knowledgebase;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCore;
import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.auth.AccessKeyCredential;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KnowledgeBaseTest {
  @ParameterizedTest
  @ValueSource(ints = {200, 403})
  void failuresAreNotEmptyResults(int status) throws Exception {
    withResponse(status, Map.of("success", false, "code", "Forbidden", "Code", "Forbidden",
        "requestId", "req-kb", "RequestId", "req-kb"), kb -> {
      var error = assertThrows(AgentCoreException.class, () -> kb.search("query").block());
      assertEquals("req-kb", error.requestId());
      assertEquals("Forbidden", error.serviceCode());
    });
  }

  @Test
  void emptyAndMalformedResultsAreDistinct() throws Exception {
    withResponse(200, Map.of("data", Map.of("chunks", List.of())), kb ->
        assertTrue(kb.search("query").block().chunks().isEmpty()));
    withResponse(200, Map.of("data", Map.of()), kb ->
        assertThrows(AgentCoreException.class, () -> kb.search("query").block()));
  }

  private void withResponse(int status, Map<String, Object> response,
      java.util.function.Consumer<KnowledgeBase> assertion) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try (var core = AgentCore.builder().workspaceId("ws-test").regionId("cn-hangzhou")
        .accessKeyCredential(new AccessKeyCredential("test-ak", "test-sk", "test-sts"))
        .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build()) {
      assertion.accept(core.knowledgeBase("kb-1"));
    } finally { server.stop(0); }
  }

  @Test
  void signedSearchPreservesSourcesAndDoesNotRetainOptions() throws Exception {
    var requests = new ArrayList<Map<String, Object>>();
    var paths = new ArrayList<String>();
    var actions = new ArrayList<String>();
    var auth = new ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      paths.add(exchange.getRequestURI().getRawPath());
      actions.add(exchange.getRequestHeaders().getFirst("x-acs-action"));
      auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = Json.write(Map.of("success", true, "data", Map.of("chunks", List.of(Map.of(
          "content", "产品说明", "assetId", "a-1", "fileName", "guide.pdf", "chunkToken", "ct-1",
          "score", 0.032, "scores", Map.of("fusion", 0.032))), "timeSpent", 12, "scoreType", "rrf")))
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try (var core = AgentCore.builder().workspaceId("ws-test").regionId("cn-hangzhou")
        .accessKeyCredential(new AccessKeyCredential("test-ak", "test-sk", "test-sts"))
        .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build()) {
      var kb = core.knowledgeBase("kb /中文"); assertTrue(requests.isEmpty());
      var options = new KnowledgeBase.SearchOptions(null, 5, "{}", false, null, null, 0.0, null);
      var result = kb.search("产品", options).block();
      assertEquals("产品说明", result.chunks().get(0).content());
      assertEquals("guide.pdf", result.chunks().get(0).fileName());
      assertEquals("ct-1", result.chunks().get(0).chunkToken());
      assertEquals(0.032, result.chunks().get(0).scores().get("fusion"));
      assertEquals("rrf", result.scoreType()); assertEquals(12L, result.timeSpent());
      kb.search("second").block();
      assertEquals("/workspaces/ws-test/knowledge-bases/kb%20%2F%E4%B8%AD%E6%96%87/search", paths.get(0));
      assertEquals("SearchKnowledgeBase", actions.get(0)); assertTrue(auth.get(0).contains("Signature="));
      assertEquals(false, requests.get(0).get("rerank")); assertEquals(0.0, requests.get(0).get("vectorWeight"));
      assertEquals(Map.of("query", "second"), requests.get(1));
    } finally { server.stop(0); }
  }
}
