package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.mcp.MCPClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class McpTransportsTest {
    @Test void stdioInitializesListsAndCallsTools() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try (var mcp = MCPClient.stdio(java,
                List.of("-cp", System.getProperty("java.class.path"), StdioMcpFixture.class.getName()), Map.of())) {
            assertEquals("echo", mcp.listTools().block(Duration.ofSeconds(10)).get(0).name());
            assertFalse(mcp.callTool("echo", Map.of()).block(Duration.ofSeconds(10)).isError());
        }
    }

    @Test void sseSendsHeadersOnHandshakeAndMessagePosts() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        var output = new AtomicReference<OutputStream>();
        var done = new CountDownLatch(1);
        var auth = new CopyOnWriteArrayList<String>();
        server.createContext("/sse", exchange -> {
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            output.set(exchange.getResponseBody());
            output.get().write("event: endpoint\ndata: /messages?sessionId=one\n\n".getBytes(StandardCharsets.UTF_8));
            output.get().flush();
            try { done.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.createContext("/messages", exchange -> {
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (request.get("id") != null) {
                String data = "event: message\ndata: " + Json.write(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                    "result", StdioMcpFixture.result(request))) + "\n\n";
                synchronized (output) { output.get().write(data.getBytes(StandardCharsets.UTF_8)); output.get().flush(); }
            }
            exchange.sendResponseHeaders(202, -1); exchange.close();
        });
        server.start();
        try (var mcp = new MCPClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sse"),
                MCPClient.Transport.SSE, () -> Mono.just(Map.of("Authorization", "Bearer fixture")))) {
            assertEquals("echo", mcp.listTools().block(Duration.ofSeconds(10)).get(0).name());
            assertFalse(mcp.callTool("echo", Map.of()).block(Duration.ofSeconds(10)).isError());
            assertTrue(auth.size() >= 4);
            assertTrue(auth.stream().allMatch("Bearer fixture"::equals));
        } finally { done.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
