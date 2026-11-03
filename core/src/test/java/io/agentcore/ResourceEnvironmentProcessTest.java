package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real public entry points with process environment and loopback OpenAPI/OSS. */
class ResourceEnvironmentProcessTest {
    @TempDir Path directory;
    @Test void loadsDefaultAndExplicitResourcesOverHttp() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var archive = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            for (var item : Map.of("manifest.json", "{}", "config/AGENTS.md", "Template prompt", "SKILL.md", "Skill prompt").entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue().getBytes()); zip.closeEntry();
            }
        }
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            byte[] result;
            if (exchange.getRequestURI().getPath().equals("/archive.zip")) result = archive.toByteArray();
            else {
                String action = exchange.getRequestHeaders().getFirst("x-acs-action");
                Object data = switch (action) {
                    case "ListModelConnections" -> Map.of("items", List.of(
                        Map.of("name", "mc-env", "connectionId", "mc-1", "protocol", "OpenAI/v1"),
                        Map.of("name", "explicit-mc", "connectionId", "mc-2", "protocol", "OpenAI/v1")));
                    case "ListModels" -> Map.of("items", List.of(Map.of("modelName", "model-env", "modelId", "model-1")));
                    case "ListMcps" -> Map.of("items", List.of(
                        Map.of("name", "mcp-a", "mcpServerId", "mcp-1"), Map.of("name", "mcp-b", "mcpServerId", "mcp-2")));
                    case "GetAgentSpec", "GetSkillDetail" -> Map.of("data", Map.of("labels", Map.of("latest", "latest-version")));
                    case "DownloadAgentSpecViaOss", "DownloadSkillVersionViaOss" -> Map.of("data", endpoint + "/archive.zip");
                    default -> Map.of("error", "Unexpected action: " + action);
                };
                result = Json.write(data).getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result); exchange.close();
        });
        Path config = directory.resolve("agent.yaml");
        Files.writeString(config, """
            apiVersion: agentteams.io/v1alpha1
            kind: AgentConfig
            metadata:
              runtimeName: runtime-test
              workspaceId: ws-test
              regionId: cn-hangzhou
            spec:
              model:
                gatewayUrl: %s/model-connection
              mcp:
                gatewayUrl: %s/mcp-servers
              credentials:
                header:
                  - key: Authorization
                    value: Bearer fixture-token
            """.formatted(endpoint, endpoint));
        server.start();
        Process process = null;
        try {
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Worker.class.getName(), config.toString(), endpoint);
            builder.environment().remove("AGENTCORE_DEBUG_TOKEN");
            builder.environment().putAll(Map.of(
                "AGENTCORE_MODEL_CONNECTION_NAME", "mc-env", "AGENTCORE_MODEL_NAME", "model-env",
                "AGENTCORE_AGENT_SPEC_NAME", "spec-env", "AGENTCORE_AGENT_SPEC_VERSION", "v-env",
                "AGENTCORE_MCP_NAMES", "[\"mcp-a\",\"mcp-b\"]",
                "AGENTCORE_SKILLS", "[{\"name\":\"s1\",\"version\":\"v1\"},{\"name\":\"s2\"}]"));
            Path log = directory.resolve("child.log");
            process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Resource selection child timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(requests.stream().anyMatch(v -> v.contains("name=mc-env")));
            assertTrue(requests.stream().anyMatch(v -> v.contains("modelName=model-env")));
            var modelQueries = requests.stream().filter(v -> v.contains("/models?")).toList();
            assertEquals(2, modelQueries.size());
            assertFalse(modelQueries.get(1).contains("modelName="));
            assertTrue(requests.stream().anyMatch(v -> v.contains("name=mcp-a")));
            assertTrue(requests.stream().anyMatch(v -> v.contains("name=mcp-b")));
            assertTrue(requests.stream().anyMatch(v -> v.contains("/agent-specs/spec-env") && v.contains("agentSpecVersion=v-env")));
            assertTrue(requests.stream().anyMatch(v -> v.contains("/skills/s1/versions/v1")));
            assertTrue(requests.stream().anyMatch(v -> v.contains("/skills/s2/versions/latest-version")));
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); server.stop(0); }
    }
    public static class Worker {
        public static void main(String[] args) {
            var config = Path.of(args[0]);
            try (var core = AgentCore.builder().configPath(config).envPath(config.resolveSibling("missing-env"))
                    .controlPlaneEndpoint(URI.create(args[1])).skillWorkspaceDir(config.resolveSibling("skills"))
                    .accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
                var timeout = Duration.ofSeconds(10);
                assertEquals("model-env", core.model().block(timeout).settings().model());
                assertEquals("model-env", core.model("explicit-mc").block(timeout).settings().model());
                assertEquals(2, core.mcpsFromEnv().block(timeout).size());
                assertEquals("v-env", core.templates().get().block(timeout).version());
                assertEquals("latest-version", core.templates().get("explicit-spec").block(timeout).version());
                assertEquals(List.of("s1", "s2"), core.skills().fromEnv().block(timeout).stream().map(io.agentcore.skill.Skill::name).toList());
            }
        }
    }
}
