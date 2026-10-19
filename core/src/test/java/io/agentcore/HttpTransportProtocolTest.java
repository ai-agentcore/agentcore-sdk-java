package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class HttpTransportProtocolTest {
    @TempDir Path directory;

    @Test void cleartextGatewayDoesNotReceiveAnH2cUpgrade() throws Exception {
        var upgrades = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean upgrade = exchange.getRequestHeaders().getFirst("Upgrade") != null;
            if (upgrade) upgrades.incrementAndGet();
            String body = upgrade ? "{\"code\":\"UpgradeRequired\"}"
                : exchange.getRequestURI().getPath().equals("/events") ? "data: {\"ok\":true}\n\ndata: [DONE]\n\n"
                : exchange.getRequestURI().getPath().equals("/blob") ? "report" : "{\"ok\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(upgrade ? 426 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var http = new HttpTransport();
            var headers = Map.of("Authorization", "Bearer fixture-token");
            assertEquals(true, http.json("Task Service", "GET", root.resolve("/task"),
                () -> Mono.just(headers), null, Duration.ofSeconds(5)).block().get("ok"));
            assertEquals(true, http.upload("Task Service upload", root.resolve("/upload"),
                () -> Mono.just(headers), "report.md", "text/markdown", BodyPublishers.ofString("report")).block().get("ok"));
            assertEquals("report", new String(http.bytes("Task Service file", root.resolve("/blob"),
                () -> Mono.just(headers)).block(), StandardCharsets.UTF_8));
            assertEquals(1, http.sse("Task Service stream", root.resolve("/events"),
                () -> Mono.just(headers), Map.of(), Duration.ofSeconds(5)).collectList().block().size());
            assertEquals("report", new String(http.download(root.resolve("/blob")).block(), StandardCharsets.UTF_8));
            Path output = directory.resolve("report.md");
            assertEquals(6L, http.downloadTo(root.resolve("/blob"), output).block());
            assertEquals("report", Files.readString(output));
            assertEquals(0, upgrades.get());
        } finally { server.stop(0); }
    }
}
