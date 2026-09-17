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

class CollaborationTest {
    @TempDir Path directory;
    String config = """
        apiVersion: agentteams.io/v1alpha1
        kind: TeamsConfig
        metadata: {runtimeName: worker-1}
        spec:
          self: {name: worker, runtimeName: worker-1, matrixUserId: '@worker:example.org'}
          matrix: {tokenEnv: TEST_MATRIX_TOKEN}
          teams:
            - name: team-1
              membership: {role: worker}
              members: []
        """;
    @Test void environmentFilePrecedenceAndFallbackThroughRealRequests() throws Exception {
        var envPath = directory.resolve("env");
        var teamsPath = directory.resolve("teams.yaml");
        Files.writeString(teamsPath, config);
        var calls = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        var output = directory.resolve("probe.log");
        // Process environment is immutable in Java; test the public auto() entry without injection hooks.
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), EnvironmentProbe.class.getName());
        builder.environment().putAll(Map.of("AGENTCORE_ENV_PATH", envPath.toString(), "AGENTCORE_TEAMS_PATH", teamsPath.toString(),
            "AGENTCORE_TASK_SERVICE_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort() + "/process",
            "TEST_MATRIX_TOKEN", "process-token"));
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Environment probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertEquals(List.of("/process/v1/tasks Bearer process-token", "/file/v1/tasks Bearer first",
                "/file/v1/tasks Bearer rotated", "/process/v1/tasks Bearer process-token"), calls);
        } finally { process.destroyForcibly(); server.stop(0); }
    }
    public static class EnvironmentProbe {
        public static void main(String[] args) throws Exception {
            var envPath = Path.of(System.getenv("AGENTCORE_ENV_PATH"));
            try (var collaboration = Collaboration.auto(envPath.getParent())) {
                var tool = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_list_tasks")).findFirst().orElseThrow();
                assertEquals(true, Json.object(tool.call(Map.of()).block(Duration.ofSeconds(5))).get("ok"));
                for (String token : List.of("first", "rotated")) {
                    Files.writeString(envPath, "export TEST_MATRIX_TOKEN='" + token + "'\nexport AGENTCORE_TASK_SERVICE_ENDPOINT='"
                        + System.getenv("AGENTCORE_TASK_SERVICE_ENDPOINT").replace("/process", "/file") + "'\n");
                    assertEquals(true, Json.object(tool.call(Map.of()).block(Duration.ofSeconds(5))).get("ok"));
                }
                Files.writeString(envPath, "export UNUSED='value'\n");
                assertEquals(true, Json.object(tool.call(Map.of()).block(Duration.ofSeconds(5))).get("ok"));
                Files.writeString(envPath, "invalid assignment\n");
                assertThrows(IllegalArgumentException.class, () -> tool.call(Map.of()).block(Duration.ofSeconds(5)));
                Files.delete(envPath);
                Files.createDirectory(envPath);
                assertThrows(RuntimeException.class, () -> tool.call(Map.of()).block(Duration.ofSeconds(5)));
            }
        }
    }
    @Test void missingAndUnassignedTeamsDoNotFailOrdinaryAgent() throws Exception {
        var provider = new TeamsProvider(directory.resolve("teams.yaml"));
        var collaboration = new Collaboration(provider, new TaskServiceClient(() -> Mono.just(Map.of())));
        var tools = collaboration.tools().block();
        assertEquals(24, tools.size());
        var query = tools.stream().filter(t -> t.name().equals("agentteams_list_tasks")).findFirst().orElseThrow();
        assertToolFailure(query.call(Map.of()).block(), "COLLABORATION_DISABLED");
        Files.writeString(directory.resolve("teams.yaml"), """
            apiVersion: agentteams.io/v1alpha1
            kind: TeamsConfig
            metadata: {runtimeName: worker-1}
            spec:
              self: {name: worker, runtimeName: worker-1}
              teams: []
            """);
        assertEquals(24, collaboration.tools().block().size());
        assertToolFailure(query.call(Map.of()).block(), "COLLABORATION_TEAM_UNAVAILABLE");
    }
    @Test void promptCompositionPreservesApplicationIdentityWithoutReadingTeams() throws Exception {
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new TeamsProvider(() -> { loads.incrementAndGet(); return Mono.just(config); });
        try (var collaboration = new Collaboration(provider, new TaskServiceClient(() -> Mono.just(Map.of())), directory)) {
            String prompt = collaboration.composePrompt("You are a travel assistant.  \n");
            assertTrue(prompt.startsWith("You are a travel assistant.\n\n## Team collaboration capabilities"));
            assertTrue(prompt.contains("not an instruction to look for"));
            assertTrue(prompt.contains("`" + directory.toAbsolutePath().normalize() + "`"));
            assertTrue(prompt.contains("`local_path` and `output_path` relative to this directory"));
            assertTrue(collaboration.composePrompt(" \n").startsWith("## Team collaboration capabilities"));
            assertEquals(24, collaboration.tools().block().size());
            assertEquals(0, loads.get());
        }
    }
    @Test void nonWorkerRoleIsDistinctFromMissingTeam() throws Exception {
        try (var collaboration = new Collaboration(new TeamsProvider(() -> Mono.just(config.replace("role: worker", "role: manager"))),
                new TaskServiceClient(() -> Mono.error(new AssertionError("No service call expected"))))) {
            var query = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_list_tasks")).findFirst().orElseThrow();
            assertToolFailure(query.call(Map.of()).block(), "COLLABORATION_ROLE_UNSUPPORTED");
        }
    }
    @Test void taskServiceFailuresAreStructuredWithoutReplayingTheOperation() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger(403);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("x-request-id", "fixture-request");
            byte[] body = "{\"message\":\"private server detail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var collaboration = new Collaboration(new TeamsProvider(() -> Mono.just(config)), new TaskServiceClient(
                () -> Mono.just(Map.of("AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "TEST_MATRIX_TOKEN", "fixture"))))) {
            var tool = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_list_tasks")).findFirst().orElseThrow();
            var expected = Map.of(401, "TASK_UNAUTHORIZED", 403, "TASK_UNAUTHORIZED", 400, "TASK_INVALID", 422, "TASK_INVALID",
                404, "TASK_NOT_FOUND", 409, "TASK_CONFLICT", 413, "FILE_TOO_LARGE", 503, "TASK_UNAVAILABLE");
            for (var entry : expected.entrySet()) {
                status.set(entry.getKey());
                var result = Json.object(tool.call(Map.of()).block(Duration.ofSeconds(5)));
                assertEquals(false, result.get("ok")); assertEquals("COLLABORATION_" + entry.getValue(), result.get("code"));
                assertEquals(entry.getKey() == 503, result.get("retryable"));
                assertFalse(Json.write(result).contains("private server detail"));
            }
            assertEquals(expected.size(), calls.get());
        } finally { server.stop(0); }
    }
    @Test void bootstrapTeamsSourceAndMatrixTokenDoNotRequireEnvironmentSecrets() throws Exception {
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new TeamsProvider(() -> { loads.incrementAndGet(); return Mono.just(config); });
        var tokens = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agentteams-app/v1/tasks", e -> {
            assertEquals("Bearer matrix-from-controller", e.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        try (var collaboration = new Collaboration(provider, new TaskServiceClient(
                () -> Mono.just(Map.of("AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort())),
                () -> { tokens.incrementAndGet(); return Mono.just("matrix-from-controller"); }), directory)) {
            var tools = collaboration.tools().block(Duration.ofSeconds(5));
            assertEquals(0, loads.get());
            var list = tools.stream().filter(t -> t.name().equals("agentteams_list_tasks")).findFirst().orElseThrow();
            assertNotNull(list.call(Map.of()).block(Duration.ofSeconds(5)));
            assertEquals(1, tokens.get()); assertEquals(1, loads.get());
        } finally { server.stop(0); }
    }
    @Test void teamsRefreshAndInvalidUpdateRetainsLastGood() throws Exception {
        Path file = directory.resolve("teams.yaml"); Files.writeString(file, config);
        var provider = new TeamsProvider(file); assertTrue(provider.snapshot().block().workerIn("team-1"));
        Files.writeString(file, "broken: ["); assertTrue(provider.snapshot().block().workerIn("team-1"));
        Files.writeString(file, config.replace("team-1", "team-2")); assertTrue(provider.snapshot().block().workerIn("team-2"));
        Files.delete(file); assertNull(provider.snapshot().block());
    }
    @Test void reusableToolUsesInvocationRoomContextAndIdempotencyWithoutServerModule() throws Exception {
        Path file = directory.resolve("teams.yaml"); Files.writeString(file, config);
        var calls = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agentteams-app", exchange -> {
            String path = exchange.getRequestURI().getPath(); calls.add(path);
            assertEquals("Bearer test-matrix", exchange.getRequestHeaders().getFirst("Authorization"));
            Map<String, Object> response;
            if (path.endsWith("/results")) {
                assertNotNull(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                var body = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("event-1", body.get("relatedRoomMessageId")); response = Map.of("id", "result-1");
            } else response = path.contains("/sub-tasks/") ? Map.of("taskId", "task-1") : Map.of("roomId", "!task-room");
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var client = new TaskServiceClient(() -> Mono.just(Map.of("AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "TEST_MATRIX_TOKEN", "test-matrix")));
            var collaboration = new Collaboration(new TeamsProvider(file), client);
            var context = new CollaborationContext("session", "team-1", "!task-room", "event-1", "task");
            var tools = collaboration.tools().block();
            var submit = tools.stream().filter(t -> t.name().equals("agentteams_submit_subtask_result")).findFirst().orElseThrow();
            assertEquals(true, Json.object(submit.call(Map.of("subtask_id", "sub-1", "summary", "Done"), Map.of(CollaborationContext.KEY, context)).block(Duration.ofSeconds(5))).get("ok"));
            assertEquals(List.of("/agentteams-app/v1/sub-tasks/sub-1", "/agentteams-app/v1/tasks/task-1", "/agentteams-app/v1/sub-tasks/sub-1/results"), calls);
            assertToolFailure(submit.call(Map.of("subtask_id", "sub-1", "summary", "Done")).block(), "COLLABORATION_CONTEXT_REQUIRED");
            var group = new CollaborationContext("session", "team-1", "!task-room", "event-1", "group");
            assertToolFailure(submit.call(Map.of("subtask_id", "sub-1", "summary", "Done"), Map.of(CollaborationContext.KEY, group)).block(), "COLLABORATION_TASK_UNAUTHORIZED");
            assertEquals(3, calls.size());
        } finally { server.stop(0); }
    }
    @Test void sharedWriteToolDoesNotMixConcurrentRoomAndEventContext() throws Exception {
        Path file = directory.resolve("teams.yaml"); Files.writeString(file, config);
        var submissions = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var keys = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agentteams-app", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> response;
            if (path.endsWith("/results")) {
                var body = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                submissions.put(path, (String) body.get("relatedRoomMessageId"));
                keys.put(path, exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                response = Map.of("id", "result");
            } else {
                String id = path.substring(path.lastIndexOf('/') + 1);
                response = path.contains("/sub-tasks/") ? Map.of("taskId", id) : Map.of("roomId", "room-" + id);
            }
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var collaboration = new Collaboration(new TeamsProvider(file), new TaskServiceClient(() -> Mono.just(Map.of(
                "AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "TEST_MATRIX_TOKEN", "test-matrix"))), directory)) {
            var submit = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_submit_subtask_result")).findFirst().orElseThrow();
            reactor.core.publisher.Flux.just("one", "two").flatMap(id -> submit.call(Map.of("subtask_id", id, "summary", "Done"),
                Map.of(CollaborationContext.KEY, new CollaborationContext(id, "team-1", "room-" + id, "event-" + id, "task"))), 2)
                .collectList().block(Duration.ofSeconds(5));
            assertEquals(Map.of("/agentteams-app/v1/sub-tasks/one/results", "event-one",
                "/agentteams-app/v1/sub-tasks/two/results", "event-two"), submissions);
            assertEquals(2, keys.values().stream().distinct().count());
            assertToolFailure(submit.call(Map.of("subtask_id", "one", "summary", "Done")).block(), "COLLABORATION_CONTEXT_REQUIRED");
            var wrongRoom = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext("s", "team-1", "wrong-room", "event", "task"));
            assertToolFailure(submit.call(Map.of("subtask_id", "one", "summary", "Done"), wrongRoom).block(), "COLLABORATION_TASK_UNAUTHORIZED");
            assertEquals(2, submissions.size());
        } finally { server.stop(0); }
    }
    @Test void unrelatedFailuresAreNotConvertedToToolResults() throws Exception {
        var failure = new IllegalStateException("provider failure");
        try (var collaboration = new Collaboration(new TeamsProvider(() -> Mono.error(failure)), new TaskServiceClient(() -> Mono.just(Map.of())))) {
            var tool = collaboration.tools().block().get(0);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> tool.call(Map.of()).block()));
        }
        var collaboration = new Collaboration(new TeamsProvider(directory.resolve("absent.yaml")), new TaskServiceClient(() -> Mono.just(Map.of())));
        var tool = collaboration.tools().block().get(0);
        collaboration.close();
        assertEquals("Collaboration client is closed", assertThrows(IllegalStateException.class, () -> tool.call(Map.of()).block()).getMessage());
    }
    @Test void teamFileToolReportsMissingOrAmbiguousContextWithoutCallingService() throws Exception {
        String twoTeams = config + "    - name: team-2\n      membership: {role: worker}\n";
        try (var collaboration = new Collaboration(new TeamsProvider(() -> Mono.just(twoTeams)),
                new TaskServiceClient(() -> Mono.error(new AssertionError("No Task Service call expected"))))) {
            var tool = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_filesync_list")).findFirst().orElseThrow();
            assertToolFailure(tool.call(Map.of("path", "shared/")).block(), "COLLABORATION_CONTEXT_REQUIRED");
            var context = new CollaborationContext("session", null, "room", "event", "dm");
            assertToolFailure(tool.call(Map.of("path", "shared/"), Map.of(CollaborationContext.KEY, context)).block(), "COLLABORATION_CONTEXT_REQUIRED");
        }
    }
    private static void assertToolFailure(Object value, String code) {
        var result = Json.object(value);
        assertEquals(false, result.get("ok")); assertEquals(code, result.get("code"));
        assertEquals(false, result.get("retryable")); assertFalse(((String) result.get("message")).isBlank());
    }
    @Test void fileToolsUploadDownloadAndKeepSignedUrlsOutOfResults() throws Exception {
        Path file = directory.resolve("teams.yaml"); Files.writeString(file, config);
        Files.writeString(directory.resolve("report.txt"), "artifact-content");
        var uploads = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Object response;
            if (path.equals("/signed")) {
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = "downloaded".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); return;
            }
            assertEquals("Bearer test-matrix", exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("PUT")) {
                assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data;"));
                uploads.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                response = Map.of("fileRef", "files/report.txt");
            } else if (path.endsWith("/download")) response = Map.of("downloadUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/signed?Signature=secret");
            else if (path.contains("/sub-tasks/")) response = Map.of("taskId", "task-1");
            else response = Map.of("roomId", "!task-room");
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var collaboration = new Collaboration(new TeamsProvider(file), new TaskServiceClient(() -> Mono.just(Map.of(
                "AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "TEST_MATRIX_TOKEN", "test-matrix"))), directory)) {
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext("s", "team-1", "!task-room", "event", "task"));
            var tools = collaboration.tools().block()
                .stream().collect(java.util.stream.Collectors.toMap(io.agentcore.tool.Tool::name, t -> t));
            assertEquals(24, tools.size());
            var result = tools.get("agentteams_write_subtask_file_from_path").call(Map.of("subtask_id", "sub-1", "path", "report.txt", "local_path", "report.txt"), context).block(Duration.ofSeconds(5));
            assertTrue(Json.write(result).contains("files/report.txt"));
            assertTrue(uploads.get(0).contains("artifact-content"));
            result = tools.get("agentteams_download_subtask_file").call(Map.of("subtask_id", "sub-1", "file_ref", "files/report.txt", "output_path", "out/report.txt"), context).block(Duration.ofSeconds(5));
            assertEquals("downloaded", Files.readString(directory.resolve("out/report.txt")));
            assertFalse(Json.write(result).contains("Signature"));
            assertThrows(IllegalArgumentException.class, () -> tools.get("agentteams_write_subtask_file_from_path")
                .call(Map.of("subtask_id", "sub-1", "path", "x", "local_path", "../outside.txt"), context).block());
            assertEquals(1, uploads.size());
            var skills = collaboration.skills().block();
            assertEquals(List.of("task-execution", "file-sharing"), skills.stream().map(io.agentcore.skill.Skill::name).toList());
            assertTrue(skills.stream().allMatch(skill -> Files.isRegularFile(skill.root().resolve("SKILL.md"))));
        } finally { server.stop(0); }
    }
    @Test void teamDirectorySyncPagesFilesAndDoesNotOverwriteWhenDisabled() throws Exception {
        Path file = directory.resolve("teams.yaml"); Files.writeString(file, config);
        Files.createDirectories(directory.resolve("source/nested"));
        Files.writeString(directory.resolve("source/a.txt"), "alpha"); Files.writeString(directory.resolve("source/nested/b.txt"), "beta");
        var remote = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agentteams-app/v1/teams/team-1/files", exchange -> {
            var query = new java.util.HashMap<String, String>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2); query.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertEquals("Bearer test-matrix", exchange.getRequestHeaders().getFirst("Authorization"));
            String path = query.get("path"); String route = exchange.getRequestURI().getPath();
            Object response;
            if (exchange.getRequestMethod().equals("PUT")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String payload = body.substring(body.indexOf("\r\n\r\n") + 4, body.lastIndexOf("\r\n--"));
                remote.put(path, payload.getBytes(StandardCharsets.UTF_8)); response = Map.of("path", path);
            } else if (route.endsWith("/stat")) {
                if (!remote.containsKey(path)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                response = Map.of("path", path);
            } else if (route.endsWith("/content")) {
                byte[] content = remote.get(path); exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content); exchange.close(); return;
            } else response = query.containsKey("cursor") ? Map.of("items", List.of(Map.of("path", "shared/reports/nested/b.txt")))
                : Map.of("items", List.of(Map.of("path", "shared/reports/a.txt")), "nextCursor", "page-two");
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var collaboration = new Collaboration(new TeamsProvider(file), new TaskServiceClient(() -> Mono.just(Map.of(
                "AGENTCORE_MATRIX_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "TEST_MATRIX_TOKEN", "test-matrix"))), directory)) {
            var context = Map.<String, Object>of(CollaborationContext.KEY, new CollaborationContext("s", "team-1", "room", "event", "group"));
            var tools = collaboration.tools().block()
                .stream().collect(java.util.stream.Collectors.toMap(io.agentcore.tool.Tool::name, t -> t));
            tools.get("agentteams_filesync_push").call(Map.of("path", "shared/reports", "local_path", "source"), context).block(Duration.ofSeconds(5));
            assertEquals(2, remote.size());
            var pull = tools.get("agentteams_filesync_pull");
            pull.call(Map.of("path", "shared/reports", "local_path", "destination"), context).block(Duration.ofSeconds(5));
            assertEquals("alpha", Files.readString(directory.resolve("destination/a.txt")));
            assertEquals("beta", Files.readString(directory.resolve("destination/nested/b.txt")));
            assertThrows(RuntimeException.class, () -> pull.call(Map.of("path", "shared/reports/a.txt", "local_path", "destination/a.txt", "overwrite", false), context).block());
            assertThrows(IllegalArgumentException.class, () -> pull.call(Map.of("path", "shared/../private", "local_path", "destination"), context).block());
            Files.createSymbolicLink(directory.resolve("linked"), directory.resolve("source"));
            assertThrows(IllegalArgumentException.class, () -> tools.get("agentteams_filesync_push").call(Map.of("path", "shared/reports", "local_path", "linked"), context).block());
            try (var paths = Files.walk(directory)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".agentcore-download-"))); }
        } finally { server.stop(0); }
    }
}
