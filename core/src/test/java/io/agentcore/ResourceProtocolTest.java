package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.mcp.MCPClient;
import io.agentcore.memory.MemoryScope;
import io.agentcore.memory.MemoryStore;
import io.agentcore.model.ModelClient;
import io.agentcore.skill.Skill;
import io.agentcore.skill.Skills;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class ResourceProtocolTest {
    HttpServer server; URI endpoint;
    @TempDir Path temporary;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void nativeModelChatAndResponsesPreserveRealStreaming() {
        var paths = new CopyOnWriteArrayList<String>();
        server.createContext("/v1", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            assertEquals("Bearer test", exchange.getRequestHeaders().getFirst("Authorization"));
            var body = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = Boolean.TRUE.equals(body.get("stream"));
            String response = stream ? "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\ndata: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}";
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        try (var core = AgentCore.builder().configPath(temporary.resolve("absent.yaml")).build()) {
            var model = core.directModel("custom", endpoint.resolve("/v1"), ModelClient.Protocol.OPENAI, () -> Mono.just(Map.of("Authorization", "Bearer test")));
            assertNotNull(model.completion(List.of(Map.of("role", "user", "content", "hi"))).block(Duration.ofSeconds(5)));
            var events = model.responsesStream("hi", Map.of()).collectList().block(Duration.ofSeconds(5));
            assertEquals("hello", events.get(0).get("delta"));
            assertEquals(List.of("/v1/chat/completions", "/v1/responses"), paths);
        }
    }
    @Test void controlPlaneUsesWorkspaceAndSignsRequestAndMemoryUsesFormBody() {
        var paths = new CopyOnWriteArrayList<String>();
        server.createContext("/workspaces", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            assertNotNull(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.startsWith("body="));
            String decoded = java.net.URLDecoder.decode(body.substring(5), StandardCharsets.UTF_8);
            assertEquals("u1", Json.object(Json.read(decoded).get("scope")).get("userId"));
            byte[] response = "{\"success\":true,\"data\":{\"memories\":[{\"memoryId\":\"m1\"}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        try (var core = AgentCore.builder().workspaceId("ws-unit").regionId("region-unit").controlPlaneEndpoint(endpoint)
                .accessKeyCredential(new AccessKeyCredential("test-ak", "test-sk")).build()) {
            assertTrue((Boolean) core.memory("store").addMemories("a preference", new MemoryScope("u1", "a1", "s1"))
                .block(Duration.ofSeconds(10)).get("success"));
            assertEquals(List.of("/workspaces/ws-unit/memorystores/store/memories"), paths);
        }
    }
    @Test void mcpReusesSessionAndPreservesToolCallResult() {
        AtomicInteger initialized = new AtomicInteger();
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            assertEquals("Bearer test", exchange.getRequestHeaders().getFirst("Authorization"));
            var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Object id = request.get("id");
            if (id == null) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            Object result = switch ((String) request.get("method")) {
                case "initialize" -> {
                    initialized.incrementAndGet();
                    yield Map.of("protocolVersion", Json.object(request.get("params")).get("protocolVersion"),
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "local", "version", "1"));
                }
                case "tools/list" -> Map.of("tools", List.of(Map.of("name", "echo", "description", "echo",
                    "inputSchema", Map.of("type", "object", "properties", Map.of()))));
                case "tools/call" -> Map.of("content", List.of(Map.of("type", "text", "text", "tool result")), "isError", false);
                default -> Map.of();
            };
            byte[] response = Json.write(Map.of("jsonrpc", "2.0", "id", id, "result", result)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Mcp-Session-Id", "session-one");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        try (var mcp = new MCPClient(endpoint.resolve("/mcp"), MCPClient.Transport.STREAMABLE_HTTP, () -> Mono.just(Map.of("Authorization", "Bearer test")))) {
            assertEquals("echo", mcp.listTools().block(Duration.ofSeconds(10)).get(0).name());
            assertFalse(mcp.callTool("echo", Map.of()).block(Duration.ofSeconds(10)).isError());
            mcp.listTools().block(Duration.ofSeconds(10));
            assertEquals(1, initialized.get());
        }
    }
    @Test void skillRejectsTraversalAndExecutesInItsDirectory() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("../escape")); zip.write(1); zip.closeEntry(); }
        assertThrows(java.io.IOException.class, () -> Skills.extract(bytes.toByteArray(), temporary));
        Files.writeString(temporary.resolve("SKILL.md"), "# Test\nUse this skill.");
        var skill = Skill.local("test", temporary);
        var tool = skill.tools().stream().filter(t -> t.name().equals("execute_command")).findFirst().orElseThrow();
        var result = Json.object(tool.call(Map.of("command", "test -f SKILL.md && printf passed")).block(Duration.ofSeconds(5)));
        assertEquals(0, result.get("exit_code")); assertEquals("passed", result.get("stdout"));
    }
    @Test void multipleSkillsUseOneToolSetAndTheSelectedWorkingDirectory() throws Exception {
        var skills = new java.util.ArrayList<Skill>();
        for (String name : List.of("first", "second")) {
            Path root = Files.createDirectories(temporary.resolve(name)); Files.writeString(root.resolve("SKILL.md"), "# " + name);
            skills.add(Skill.local(name, root));
        }
        var tools = Skill.tools(skills, temporary).stream().collect(java.util.stream.Collectors.toMap(io.agentcore.tool.Tool::name, value -> value));
        assertEquals(3, tools.size());
        assertEquals(2, ((List<?>) Json.object(tools.get("load_skills").call(Map.of()).block()).get("skills")).size());
        assertEquals("# second", Json.object(tools.get("read_skill_file").call(Map.of("name", "second", "relative_path", "SKILL.md")).block()).get("content"));
        assertEquals(0, Json.object(tools.get("execute_command").call(Map.of("command", "test -f first/SKILL.md && test -f second/SKILL.md")).block()).get("exit_code"));
        assertThrows(IllegalArgumentException.class, () -> Skill.tools(List.of(skills.get(0), skills.get(0)), temporary));
    }
}
