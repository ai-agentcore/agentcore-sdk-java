package io.agentcore.runtime;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCoreException;
import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.auth.ControllerCredentials;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class ControlConfigFallbackTest {
    @TempDir Path directory;

    @Test void fallbackUsesOnlyAvailabilityFailures() throws Exception {
        var hosts = directory.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 fixture.oss-cn-hangzhou-internal.aliyuncs.com fixture.oss-cn-hangzhou.aliyuncs.com\n"
            + "127.0.0.1 fixture.oss-cn-shanghai.aliyuncs.com\n");
        var output = directory.resolve("output");
        // DNS configuration is JVM-wide: isolate these loopback-only endpoints from other tests.
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djdk.net.hosts.file=" + hosts, "-cp", System.getProperty("java.class.path"), Probe.class.getName())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "OSS fallback probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); } catch (Exception error) { return error.toString(); }
            });
        } finally { process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            for (int status : List.of(403, 404, 503)) check(status, "cn-hangzhou", false);
            check(503, "cn-hangzhou", true);
            check(200, "cn-shanghai", false); // Internal endpoint DNS fails; public endpoint succeeds.
        }
        private static void check(int status, String region, boolean custom) throws Exception {
            var contacted = new CopyOnWriteArrayList<String>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            String root = "http://127.0.0.1:" + port;
            String endpoint = custom ? root : "http://oss-" + region + "-internal.aliyuncs.com:" + port;
            server.createContext("/api/v1/credentials/sts", e -> {
                byte[] body = Json.write(Map.of("oss_endpoint", endpoint, "oss_bucket", "fixture",
                    "access_key_id", "fixture-ak", "access_key_secret", "fixture-sk", "security_token", "fixture-sts",
                    "expiration", Instant.now().plusSeconds(3600).toString(), "agent_config_path", "agent.yaml", "teams_config_path", "teams.yaml"))
                    .getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
            });
            server.createContext("/", e -> {
                String host = e.getRequestHeaders().getFirst("Host"); contacted.add(host);
                int response = custom || host.contains("-internal") ? status : 200;
                e.getResponseHeaders().set("x-acs-request-id", "fixture-request");
                byte[] body = "kind: TeamsConfig".getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(response, body.length); e.getResponseBody().write(body); e.close();
            });
            server.start();
            var http = new HttpTransport();
            try (var loader = new ControlConfigLoader(
                    new ControllerCredentials(root, () -> Mono.just("fixture-sa"), http), http)) {
                if (status == 403 || custom) {
                    var error = assertThrows(AgentCoreException.class, () -> loader.loadTeams().block(Duration.ofSeconds(5)));
                    assertEquals(status, error.status()); assertEquals("fixture-request", error.requestId());
                } else if (status == 404) assertNull(loader.loadTeams().block(Duration.ofSeconds(5)));
                else assertEquals("kind: TeamsConfig", loader.loadTeams().block(Duration.ofSeconds(5)));
                assertEquals(status == 503 && !custom ? 2 : 1, contacted.size(), contacted.toString());
                if (!custom && status >= 400 && status < 500) assertTrue(contacted.get(0).contains("-internal"));
            } finally { server.stop(0); }
        }
    }
}
