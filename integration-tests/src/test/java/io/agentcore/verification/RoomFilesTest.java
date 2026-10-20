package io.agentcore.verification;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.Json;
import io.agentcore.collaboration.Collaboration;
import io.agentcore.collaboration.CollaborationContext;
import io.agentcore.collaboration.TaskServiceClient;
import io.agentcore.collaboration.TeamsProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class RoomFilesTest {
    @TempDir Path directory;
    static final String CONFIG = """
        apiVersion: agentteams.io/v1alpha1
        kind: TeamsConfig
        metadata: {runtimeName: worker}
        spec:
          self: {name: worker, runtimeName: worker, matrixUserId: '@worker:ws'}
          matrix: {tokenEnv: MATRIX_TOKEN}
          teams: []
        """;
    @Test void roomFilesWorkWithoutTeamsAndUseNativeMatrixMedia() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var bodies = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            Object body = path.endsWith("/config") ? Map.of("m.upload.size", 100)
                : path.contains("/event/") ? Map.of("type", "m.room.message", "room_id", "!room", "sender", "@human:ws",
                    "content", Map.of("msgtype", "m.file", "body", "../data.txt", "url", "mxc://ws/media"))
                : path.contains("/upload") ? Map.of("content_uri", "mxc://ws/media") : Map.of("event_id", "$sent");
            byte[] bytes = (path.contains("/download/") ? "hello" : Json.write(body)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        Path config = directory.resolve("teams.yaml"); Files.writeString(config, CONFIG);
        var tasks = new TaskServiceClient(() -> Mono.just(Map.of("AGENTTEAMS_MATRIX_URL",
            "http://127.0.0.1:" + server.getAddress().getPort(), "MATRIX_TOKEN", "secret")));
        try (var collaboration = new Collaboration(new TeamsProvider(config), tasks, directory)) {
            var tools = collaboration.tools().block();
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext(null, null, "!room", "$request", "group"));
            var download = tools.stream().filter(t -> t.name().equals("agentteams_download_room_file")).findFirst().orElseThrow();
            var result = Json.object(download.call(Map.of("event_id", "$file"), context).block(Duration.ofSeconds(5)));
            assertEquals(true, result.get("ok"));
            var data = Json.object(result.get("data"));
            assertEquals("hello", Files.readString(Path.of((String) data.get("local_path"))));
            assertEquals("data.txt", data.get("filename"));
            assertFalse(Json.write(data).contains("mxc://"));
            Files.writeString(directory.resolve("image.png"), "image");
            var send = tools.stream().filter(t -> t.name().equals("agentteams_send_room_file")).findFirst().orElseThrow();
            result = Json.object(send.call(Map.of("local_path", "image.png", "caption", "result"), context).block(Duration.ofSeconds(5)));
            assertEquals(Map.of("ok", true, "data", Map.of("event_id", "$sent", "filename", "image.png", "status", "sent")), result);
            assertEquals("image", bodies.get(4));
            var content = Json.read(bodies.get(5));
            assertEquals("m.image", content.get("msgtype"));
            assertEquals(Map.of("m.in_reply_to", Map.of("event_id", "$request")), content.get("m.relates_to"));
            assertFalse(content.containsKey("m.mentions"));
            assertEquals("COLLABORATION_CONTEXT_REQUIRED", Json.object(send.call(Map.of("local_path", "image.png")).block()).get("code"));
        } finally { server.stop(0); }
    }

    @Test void actualSizeLimitAndHttpFailuresAreStructuredAndCleanUpFiles() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String path = e.getRequestURI().getPath();
            Object response = path.endsWith("/config") ? Map.of("m.upload.size", 2)
                : Map.of("type", "m.room.message", "room_id", "!room", "sender", "@human:ws",
                    "content", Map.of("msgtype", "m.file", "body", "data", "url", "mxc://ws/media"));
            byte[] bytes = (status.get() != 200 ? "private upstream error"
                : path.contains("/download/") ? "hello" : Json.write(response)).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status.get(), bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        try (var collaboration = create(server)) {
            var download = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_download_room_file")).findFirst().orElseThrow();
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext(null, null, "!room", "$request", "group"));
            var result = Json.object(download.call(Map.of("event_id", "$file"), context).block(Duration.ofSeconds(5)));
            assertEquals("COLLABORATION_FILE_TOO_LARGE", result.get("code"));
            try (var paths = Files.walk(directory)) { assertEquals(0, paths.filter(Files::isRegularFile).count()); }
            for (var entry : Map.of(403, "UNAUTHORIZED", 404, "NOT_FOUND", 503, "UNAVAILABLE").entrySet()) {
                status.set(entry.getKey());
                result = Json.object(download.call(Map.of("event_id", "$file"), context).block(Duration.ofSeconds(5)));
                assertEquals("COLLABORATION_ROOM_FILE_" + entry.getValue(), result.get("code"));
                assertFalse(Json.write(result).contains("private"));
            }
        } finally { server.stop(0); }
    }

    @Test void uncertainSendDoesNotUploadAgainOrChangeTokenOrTransaction() throws Exception {
        var uploads = new java.util.concurrent.atomic.AtomicInteger();
        var sends = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String path = e.getRequestURI().toString();
            byte[] request = e.getRequestBody().readAllBytes();
            if (path.contains("/send/")) {
                sends.add(path + "|" + e.getRequestHeaders().getFirst("Authorization") + "|" + new String(request, StandardCharsets.UTF_8));
                e.close(); return;
            }
            if (path.contains("/upload")) uploads.incrementAndGet();
            byte[] response = Json.write(path.contains("/config") ? Map.of("m.upload.size", 100)
                : Map.of("content_uri", "mxc://ws/media")).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        }); server.start();
        Files.writeString(directory.resolve("file.txt"), "result");
        try (var collaboration = create(server)) {
            var send = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_send_room_file")).findFirst().orElseThrow();
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext(null, null, "!room", "$request", "group"));
            var result = Json.object(send.call(Map.of("local_path", "file.txt"), context).block(Duration.ofSeconds(5)));
            assertEquals("COLLABORATION_ROOM_FILE_SEND_UNKNOWN", result.get("code"));
            assertEquals(false, result.get("retryable"));
            assertEquals(1, uploads.get());
            assertTrue(sends.size() >= 2);
            assertEquals(1, sends.stream().distinct().count());
            result = Json.object(send.call(Map.of("local_path", "../outside"), context).block(Duration.ofSeconds(5)));
            assertEquals("COLLABORATION_ARGUMENT_INVALID", result.get("code"));
            assertEquals(1, uploads.get());
        } finally { server.stop(0); }
    }

    @Test void cancelledDownloadClosesStreamAndRemovesTemporaryFile() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String path = e.getRequestURI().getPath();
            if (path.contains("/download/")) {
                e.sendResponseHeaders(200, 100);
                e.getResponseBody().write("hello".getBytes(StandardCharsets.UTF_8)); e.getResponseBody().flush(); started.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                e.close(); return;
            }
            Object body = path.endsWith("/config") ? Map.of("m.upload.size", 100)
                : Map.of("type", "m.room.message", "room_id", "!room", "sender", "@human:ws",
                    "content", Map.of("msgtype", "m.file", "body", "data", "url", "mxc://ws/media"));
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        try (var collaboration = create(server)) {
            var download = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_download_room_file")).findFirst().orElseThrow();
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext(null, null, "!room", "$request", "group"));
            var subscription = download.call(Map.of("event_id", "$file"), context).subscribe();
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            subscription.dispose(); release.countDown();
            for (int attempt = 0; attempt < 100; attempt++) {
                try (var paths = Files.walk(directory)) { if (paths.noneMatch(Files::isRegularFile)) return; }
                Thread.sleep(20);
            }
            fail("Download temporary file was not cleaned after cancellation");
        } finally { release.countDown(); server.stop(0); }
    }

    private Collaboration create(HttpServer server) {
        return new Collaboration(new TeamsProvider(() -> Mono.just(CONFIG)), new TaskServiceClient(() -> Mono.just(Map.of(
            "AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "MATRIX_TOKEN", "secret"))), directory);
    }

    @Test void refreshesRejectedCredentialAndRejectsEncryptedEventsBeforeMediaAccess() throws Exception {
        var token = new java.util.concurrent.atomic.AtomicReference<>("old");
        var seen = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String auth = e.getRequestHeaders().getFirst("Authorization");
            seen.add(auth);
            if (auth.equals("Bearer old")) {
                token.set("new"); e.sendResponseHeaders(401, -1); e.close(); return;
            }
            byte[] bytes = Json.write(Map.of("type", "m.room.message", "room_id", "!room",
                "content", Map.of("msgtype", "m.file", "body", "file", "file", Map.of("url", "mxc://ws/encrypted"))))
                .getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        try (var collaboration = new Collaboration(new TeamsProvider(() -> Mono.just(CONFIG)),
                new TaskServiceClient(() -> Mono.just(Map.of("AGENTCORE_MATRIX_URL",
                    "http://127.0.0.1:" + server.getAddress().getPort())), () -> Mono.just(token.get())), directory)) {
            var tool = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_download_room_file")).findFirst().orElseThrow();
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext(null, null, "!room", "$event", "group"));
            var result = Json.object(tool.call(Map.of("event_id", "$file"), context).block(Duration.ofSeconds(5)));
            assertEquals("COLLABORATION_ROOM_FILE_UNSUPPORTED", result.get("code"));
            assertEquals(List.of("Bearer old", "Bearer new"), seen);
        } finally { server.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"spring", "langchain4j", "agentscope"})
    void nativeFrameworksKeepConcurrentRoomContextsSeparate(String framework) throws Exception {
        var delivered = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String path = e.getRequestURI().getPath();
            byte[] input = e.getRequestBody().readAllBytes();
            if (path.contains("/send/")) {
                var content = Json.read(new String(input, StandardCharsets.UTF_8));
                var reply = Json.object(Json.object(content.get("m.relates_to")).get("m.in_reply_to"));
                delivered.put((String) reply.get("event_id"), path);
            }
            Object body = path.endsWith("/config") ? Map.of("m.upload.size", 100)
                : path.contains("/upload") ? Map.of("content_uri", "mxc://ws/media") : Map.of("event_id", "$sent");
            byte[] response = Json.write(body).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        }); server.start();
        Files.writeString(directory.resolve("file.txt"), "result");
        try (var collaboration = create(server)) {
            var tool = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_send_room_file")).findFirst().orElseThrow();
            reactor.core.publisher.Flux.just("one", "two").flatMap(id -> Mono.fromCallable(() -> {
                var metadata = Map.<String, Object>of(CollaborationContext.KEY,
                    new CollaborationContext(null, null, "!room-" + id, "$request-" + id, "group"));
                var arguments = Map.<String, Object>of("local_path", "file.txt");
                if (framework.equals("spring")) return io.agentcore.springai.AgentCoreSpringAI.tool(tool)
                    .call(Json.write(arguments), new org.springframework.ai.chat.model.ToolContext(metadata));
                if (framework.equals("langchain4j")) return io.agentcore.langchain4j.AgentCoreLangChain4j.tools(List.of(tool))
                    .values().iterator().next().executeWithContext(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id(id).name(tool.name()).arguments(Json.write(arguments)).build(),
                        dev.langchain4j.invocation.InvocationContext.builder().invocationParameters(
                            dev.langchain4j.invocation.InvocationParameters.from(metadata)).build()).resultText();
                return io.agentcore.agentscope.AgentCoreAgentScope.tool(tool).callAsync(
                    io.agentscope.core.tool.ToolCallParam.builder().input(arguments)
                        .toolUseBlock(io.agentscope.core.message.ToolUseBlock.builder().id(id).name(tool.name()).input(arguments).build())
                        .runtimeContext(io.agentscope.core.agent.RuntimeContext.builder().sessionId(id).putAll(metadata).build())
                        .build()).block(Duration.ofSeconds(5)).toString();
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()), 2).collectList().block(Duration.ofSeconds(10));
            assertEquals(2, delivered.size());
            for (String id : List.of("one", "two")) assertTrue(delivered.get("$request-" + id).contains("/!room-" + id + "/"));
        } finally { server.stop(0); }
    }
}
