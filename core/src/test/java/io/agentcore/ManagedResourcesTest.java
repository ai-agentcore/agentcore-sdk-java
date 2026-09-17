package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedResourcesTest {
    @TempDir Path directory;
    @Test void resolvesNamedResourcesOnceAndUsesTheirActualGatewayRoutes() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var operations = new CopyOnWriteArrayList<String>(); var gatewayPaths = new CopyOnWriteArrayList<String>();
        var archive = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("fixture/SKILL.md")); zip.write("---\nname: fixture\ndescription: Useful fixture\n---\n# Fixture".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Object response;
            if (path.startsWith("/workspaces/")) {
                String action = exchange.getRequestHeaders().getFirst("x-acs-action"); operations.add(action);
                assertFalse(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer"));
                response = switch (action) {
                    case "ListModelConnections" -> Map.of("items", List.of(Map.of("name", "model-fixture", "connectionId", "mc-fixture", "protocol", "openai/v1")));
                    case "ListModels" -> Map.of("items", List.of(Map.of("modelName", "model-wire", "modelId", "model-id", "maxTokens", 8192,
                        "contextSize", 32768, "capabilities", Map.of("toolCalls", true, "vision", false))));
                    case "ListMcps" -> Map.of("items", List.of(Map.of("name", "mcp-fixture", "mcpServerId", "mcp-id")));
                    case "GetSkillDetail" -> Map.of("data", Map.of("labels", Map.of("latest", "v1")));
                    case "DownloadSkillVersionViaOss" -> Map.of("data", base + "/archive?Signature=fixture");
                    case "ListCredentials" -> Map.of("items", List.of(Map.of("name", "outside", "credentialType", "mcpHeader", "resourceScope", "SPECIFIED", "resourceRefs", List.of())));
                    default -> throw new AssertionError(action);
                };
            } else if (path.equals("/archive")) {
                assertNull(exchange.getRequestHeaders().getFirst("Authorization")); byte[] bytes = archive.toByteArray();
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); return;
            } else {
                assertEquals("Bearer gateway-fixture", exchange.getRequestHeaders().getFirst("Authorization"));
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
                gatewayPaths.add(path);
                var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (path.equals("/model-connection/mc-fixture/v1/chat/completions")) {
                    assertEquals("model-wire", request.get("model")); response = Map.of("choices", List.of(Map.of("message", Map.of("content", "ok"))));
                } else {
                    assertEquals("/mcp-servers/mcp-id", path); assertEquals("fixture", exchange.getRequestHeaders().getFirst("X-App"));
                    if (request.get("id") == null) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
                    response = Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", StdioMcpFixture.result(request));
                }
            }
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        Path config = directory.resolve("agent.yaml"); Files.writeString(config, """
            apiVersion: agentteams.io/v1alpha1
            kind: AgentConfig
            metadata: {runtimeName: fixture, workspaceId: ws-fixture, regionId: cn-hangzhou}
            spec:
              model: {gatewayUrl: '%s/model-connection'}
              mcp: {gatewayUrl: '%s/mcp-servers'}
              credentials: {header: [{key: Authorization, value: 'Bearer gateway-fixture'}]}
            """.formatted(base, base));
        Path materialized;
        try (var core = AgentCore.builder().configPath(config).controlPlaneEndpoint(URI.create(base))
                .skillWorkspaceDir(directory.resolve("skills")).accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            var model = core.model("model-fixture", "model-wire").block(Duration.ofSeconds(5));
            assertEquals(32768, model.descriptor().contextSize()); assertEquals("model-id", model.descriptor().modelId());
            assertEquals(Map.of("toolCalls", true, "vision", false), model.descriptor().capabilities());
            for (int i = 0; i < 2; i++) model.completion(List.of(Map.of("role", "user", "content", "hi"))).block(Duration.ofSeconds(5));
            var mcp = core.mcp("mcp-fixture", null, Map.of("X-App", "fixture")).block(Duration.ofSeconds(5));
            assertEquals("echo", mcp.listTools().block(Duration.ofSeconds(5)).get(0).name());
            assertFalse(mcp.callTool("echo", Map.of()).block(Duration.ofSeconds(5)).isError());
            var pair = reactor.core.publisher.Mono.zip(core.skills().load("skill-fixture"), core.skills().load("skill-fixture")).block(Duration.ofSeconds(5));
            var skill = pair.getT1(); assertSame(skill, pair.getT2());
            assertEquals("v1", skill.version()); assertEquals("# Fixture", skill.instruction()); materialized = skill.root();
            assertEquals("Useful fixture", skill.description()); assertTrue(materialized.startsWith(directory.resolve("skills").toRealPath()));
            assertSame(skill, core.skills().load("skill-fixture").block(Duration.ofSeconds(5)));
            assertEquals(1, operations.stream().filter("GetSkillDetail"::equals).count());
            assertEquals(1, operations.stream().filter("DownloadSkillVersionViaOss"::equals).count());
            assertThrows(IllegalArgumentException.class, () -> core.credentials().forMcp("outside", "mcp-id").block(Duration.ofSeconds(5)));
            assertEquals(1, operations.stream().filter("ListModelConnections"::equals).count());
            assertEquals(1, operations.stream().filter("ListModels"::equals).count());
            assertEquals(1, operations.stream().filter("ListMcps"::equals).count());
            try (var explicit = AgentCore.builder().workspaceId("ws-explicit").regionId("cn-hangzhou")
                    .controlPlaneEndpoint(URI.create(base)).accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
                var modelError = assertThrows(IllegalStateException.class,
                    () -> explicit.model("model-fixture", "model-wire").block(Duration.ofSeconds(1)));
                assertTrue(modelError.getMessage().contains("runtime configuration"), modelError.getMessage());
                var mcpError = assertThrows(IllegalStateException.class,
                    () -> explicit.mcp("mcp-fixture").block(Duration.ofSeconds(1)));
                assertTrue(mcpError.getMessage().contains("runtime configuration"), mcpError.getMessage());
            }
        } finally { server.stop(0); }
        assertFalse(Files.exists(materialized));
        assertTrue(Files.isDirectory(directory.resolve("skills")), "The caller-owned workspace must survive close");
        assertTrue(gatewayPaths.contains("/model-connection/mc-fixture/v1/chat/completions"));
    }
}
