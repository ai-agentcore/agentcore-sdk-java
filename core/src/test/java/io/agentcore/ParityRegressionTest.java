package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.memory.MemoryStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ParityRegressionTest {
    @TempDir Path directory;
    @Test void runtimeEnvSuppliesControlPlaneEndpoint() throws Exception {
        Path config = directory.resolve("agent.yaml");
        Files.writeString(config, """
            apiVersion: agentteams.io/v1alpha1
            kind: AgentConfig
            metadata: {runtimeName: fixture, workspaceId: ws-fixture, regionId: cn-hangzhou}
            spec:
              model: {gatewayUrl: 'https://gateway.example.com/model-connection'}
              mcp: {gatewayUrl: 'https://gateway.example.com/mcp-servers'}
              credentials: {header: [{key: Authorization, value: 'Bearer fixture'}]}
            """);
        Path env = directory.resolve("env");
        Files.writeString(env, "export AGENTCORE_CONTROL_ENDPOINT='http://127.0.0.1:12345'\n");
        try (var core = AgentCore.builder().configPath(config).envPath(env)
                .accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            var client = core.controlPlane().block(Duration.ofSeconds(3));
            var endpoint = client.getClass().getDeclaredField("endpoint"); endpoint.setAccessible(true);
            assertEquals(URI.create("http://127.0.0.1:12345"), endpoint.get(client));
        }
        var builder = AgentCore.builder().configPath(config).envPath(env).controlPlaneEndpoint(URI.create("http://127.0.0.1:12346"))
            .accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk"));
        try (var core = builder.build()) {
            builder.controlPlaneEndpoint(URI.create("http://127.0.0.1:12347"));
            var client = core.controlPlane().block(Duration.ofSeconds(3));
            var endpoint = client.getClass().getDeclaredField("endpoint"); endpoint.setAccessible(true);
            assertEquals(URI.create("http://127.0.0.1:12346"), endpoint.get(client));
        }
    }
    @Test void explicitBusinessRejectionIsNotAnUnknownWrite() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = Json.write(Map.of("success", false, "code", "InvalidParameter", "requestId", "fixture-id"))
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var core = AgentCore.builder().workspaceId("ws-fixture").regionId("cn-hangzhou")
                .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            var error = assertThrows(AgentCoreException.class,
                () -> core.memory("store").addMemories("text", null).block(Duration.ofSeconds(5)));
            assertFalse(error instanceof MemoryStore.AddMemoriesOutcomeUnknownException);
            assertEquals("fixture-id", error.requestId());
            assertEquals("InvalidParameter", error.serviceCode());
        } finally { server.stop(0); }
    }
    @Test void memoryOptionsAndSessionPaginationReachTheWire() throws Exception {
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        var queries = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!raw.isEmpty()) bodies.add(Json.read(java.net.URLDecoder.decode(raw.substring(5), StandardCharsets.UTF_8)));
            if (exchange.getRequestURI().getRawQuery() != null) queries.add(java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] body = "{\"success\":true,\"data\":{\"items\":[],\"nextToken\":\"page+2\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var core = AgentCore.builder().workspaceId("ws-fixture").regionId("cn-hangzhou")
                .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            var store = core.memory("store"); var scope = new io.agentcore.memory.MemoryScope("user", "agent", "session");
            var metadata = Map.of("category", "preference");
            store.addMemories("text", scope, metadata).block(Duration.ofSeconds(5));
            store.addMessages(java.util.List.of(Map.of("role", "user", "content", "text")), scope, metadata).block(Duration.ofSeconds(5));
            store.searchMemories("query", scope, new MemoryStore.SearchOptions(8, metadata, true, 0.3, 0.2)).block(Duration.ofSeconds(5));
            var page = store.listMemorySessionMessages("session", scope, 10, null).block(Duration.ofSeconds(5));
            store.listMemorySessionMessages("session", scope, 10, (String) Json.object(page.get("data")).get("nextToken")).block(Duration.ofSeconds(5));
            assertEquals(metadata, bodies.get(0).get("metadata")); assertEquals(metadata, bodies.get(1).get("metadata"));
            assertEquals(metadata, bodies.get(2).get("metadata")); assertEquals(true, bodies.get(2).get("enableRerank"));
            assertEquals(0.3, bodies.get(2).get("minSimilarity")); assertEquals(0.2, bodies.get(2).get("minScore")); assertEquals(8, bodies.get(2).get("topK"));
            assertTrue(queries.get(1).contains("nextToken=page+2")); assertTrue(queries.get(1).contains("maxResults=10"));
            assertTrue(queries.get(1).contains("sessionId=session")); assertTrue(queries.get(1).contains("userId=user"));
        } finally { server.stop(0); }
    }
    @Test void unknownWritePreservesServiceCodeRequestIdAndCause() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"Code\":\"ServiceUnavailable\",\"RequestId\":\"fixture-503\",\"Message\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var core = AgentCore.builder().workspaceId("ws-fixture").regionId("cn-hangzhou")
                .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            var error = assertThrows(MemoryStore.AddMemoriesOutcomeUnknownException.class,
                () -> core.memory("store").addMemories("text", null).block(Duration.ofSeconds(5)));
            assertEquals("fixture-503", error.requestId()); assertEquals("ServiceUnavailable", error.serviceCode());
            assertEquals(503, error.status()); assertNotNull(error.getCause()); assertNotNull(error.getCause().getCause());
        } finally { server.stop(0); }
    }
    @Test void localSkillDescriptionAndApprovalAreAppliedBeforeExecuting() throws Exception {
        Path root = Files.createDirectory(directory.resolve("skill"));
        Path realRoot = root.toRealPath();
        Files.writeString(root.resolve("SKILL.md"), "---\nname: fixture\ndescription: Read local files\n---\nInstructions");
        try (var core = AgentCore.auto()) {
            var skill = core.skills().local(directory).block().get(0);
            assertEquals("fixture", skill.name()); assertEquals("Read local files", skill.description());
            var tools = skill.tools((command, cwd) -> { assertEquals(realRoot, cwd); return false; }, 300);
            var command = tools.stream().filter(t -> t.name().equals("execute_command")).findFirst().orElseThrow();
            var result = Json.object(command.call(Map.of("command", "touch should-not-exist")).block());
            assertTrue(result.containsKey("error")); assertFalse(Files.exists(root.resolve("should-not-exist")));
            var load = tools.stream().filter(t -> t.name().equals("load_skills")).findFirst().orElseThrow();
            assertEquals(realRoot.toString(), Json.object(load.call(Map.of("name", "fixture")).block()).get("directory"));
        }
        assertTrue(Files.exists(root.resolve("SKILL.md")));
    }
}
