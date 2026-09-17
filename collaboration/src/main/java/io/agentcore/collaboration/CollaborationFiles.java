package io.agentcore.collaboration;

import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** File operations scoped to a user-selected workspace; Task Service owns remote authorization. */
final class CollaborationFiles {
    private final TaskServiceClient tasks;
    private final Path workspace;
    CollaborationFiles(TaskServiceClient tasks, Path workspace) { this.tasks = tasks; this.workspace = workspace.toAbsolutePath().normalize(); }

    Mono<Map<String, Object>> upload(TeamsProvider.Snapshot snapshot, Map<String, Object> args, boolean fromPath) {
        return Mono.fromCallable(() -> {
            String remote = Json.text(args.get("path"), "path");
            var publisher = fromPath ? BodyPublishers.ofFile(local(Json.text(args.get("local_path"), "local_path")))
                : BodyPublishers.ofByteArray(content(args));
            String contentType = args.get("content_type") instanceof String value ? value : "application/octet-stream";
            return tasks.upload(snapshot, "/v1/sub-tasks/" + TaskServiceClient.id(Json.text(args.get("subtask_id"), "subtask_id")) + "/files",
                remote, Path.of(remote).getFileName().toString(), contentType, publisher);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(value -> value);
    }
    Mono<Map<String, Object>> read(TeamsProvider.Snapshot snapshot, String endpoint, String fileRef) {
        return tasks.readFile(snapshot, endpoint + "/content", Map.of("fileRef", fileRef)).map(bytes -> {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                return Map.<String, Object>of("content", text, "encoding", "utf-8");
            } catch (java.nio.charset.CharacterCodingException binary) {
                return Map.<String, Object>of("content", Base64.getEncoder().encodeToString(bytes), "encoding", "base64");
            }
        });
    }
    Mono<Map<String, Object>> download(TeamsProvider.Snapshot snapshot, String endpoint, Map<String, Object> args) {
        return store(Json.text(args.get("output_path"), "output_path"), !Boolean.FALSE.equals(args.get("overwrite")),
            temporary -> tasks.download(snapshot, endpoint + "/download", Json.text(args.get("file_ref"), "file_ref"), temporary));
    }
    Mono<Map<String, Object>> sync(TeamsProvider.Snapshot snapshot, String team, String action, Map<String, Object> args) {
        String path = remote(Json.text(args.get("path"), "path"));
        String endpoint = "/v1/teams/" + TaskServiceClient.id(team) + "/files";
        if (action.equals("list")) return tasks.request(snapshot, "GET", endpoint, page(args, "path", path), null, null);
        if (action.equals("stat")) return stat(snapshot, endpoint, path);
        if (action.equals("push")) return Mono.fromCallable(() -> {
            Path source = local(Json.text(args.get("local_path"), "local_path"));
            if (Files.isRegularFile(source)) return List.of(source);
            try (var files = Files.walk(source)) { return files.filter(p -> !Files.isDirectory(p)).sorted().toList(); }
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable).concatMap(source -> Mono.fromCallable(() -> {
            Path base = local(Json.text(args.get("local_path"), "local_path"));
            Path checked = local(source.toString());
            String target = Files.isDirectory(base) ? path + "/" + base.relativize(checked).toString().replace('\\', '/') : path;
            String type = Files.probeContentType(checked);
            return tasks.upload(snapshot, endpoint, remote(target), checked.getFileName().toString(),
                type == null ? "application/octet-stream" : type, BodyPublishers.ofFile(checked))
                .map(result -> Map.<String, Object>of("path", target, "localPath", checked.toString(), "result", result));
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(value -> value)).collectList().map(files -> summary(action, path, files));
        return stat(snapshot, endpoint, path).flatMapMany(metadata -> "directory".equals(metadata.get("kind"))
            ? entries(snapshot, endpoint, path).flatMapMany(Flux::fromIterable).map(entry -> Map.entry(entry, false))
            : Flux.just(Map.entry(Map.<String, Object>of("path", path), true))).concatMap(selection -> {
                String selected = remote(Json.text(selection.getKey().get("path"), "path"));
                String base = Json.text(args.get("local_path"), "local_path");
                String output = selection.getValue() ? base : Path.of(base).resolve(selected.substring(path.length() + 1)).toString();
                return store(output, !Boolean.FALSE.equals(args.get("overwrite")), temporary ->
                    tasks.readFile(snapshot, endpoint + "/content", Map.of("path", selected)).flatMap(bytes -> Mono.fromCallable(() -> {
                        Files.write(temporary, bytes); return (long) bytes.length;
                    }).subscribeOn(Schedulers.boundedElastic()))).map(result -> Map.<String, Object>of("path", selected, "output", result));
            }).collectList().map(files -> summary(action, path, files));
    }
    private Mono<Map<String, Object>> stat(TeamsProvider.Snapshot snapshot, String endpoint, String path) {
        Mono<Map<String, Object>> directory = entries(snapshot, endpoint, path).map(items -> Map.of("kind", "directory", "path", path, "entries", items.size()));
        if (path.equals("shared")) return directory;
        return tasks.request(snapshot, "GET", endpoint + "/stat", Map.of("path", path), null, null)
            .map(value -> { var result = new LinkedHashMap<>(value); result.put("kind", "file"); return (Map<String, Object>) result; })
            .onErrorResume(AgentCoreException.class, error -> {
                if (!Integer.valueOf(404).equals(error.status()) && !Integer.valueOf(409).equals(error.status())) return Mono.error(error);
                return directory.flatMap(value -> ((Number) value.get("entries")).intValue() == 0 ? Mono.error(error) : Mono.just(value));
            });
    }
    private Mono<List<Map<String, Object>>> entries(TeamsProvider.Snapshot snapshot, String endpoint, String path) {
        return tasks.request(snapshot, "GET", endpoint, Map.of("path", path, "limit", "100"), null, null)
            .expand(page -> page.get("nextCursor") instanceof String cursor && !cursor.isEmpty()
                ? tasks.request(snapshot, "GET", endpoint, Map.of("path", path, "limit", "100", "cursor", cursor), null, null) : Mono.empty())
            .flatMapIterable(page -> (List<?>) page.getOrDefault("items", List.of())).map(Json::object).map(entry -> {
                String selected = remote(Json.text(entry.get("path"), "path"));
                if (!selected.startsWith(path + "/")) throw new IllegalArgumentException("Team file is outside the requested directory");
                return entry;
            }).collectList();
    }
    private Mono<Map<String, Object>> store(String output, boolean overwrite, java.util.function.Function<Path, Mono<Long>> download) {
        return Mono.defer(() -> {
            final Path target;
            try { target = local(output); } catch (Exception error) { return Mono.error(error); }
            return Mono.usingWhen(Mono.fromCallable(() -> {
                if (!overwrite && Files.exists(target)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
                Files.createDirectories(target.getParent());
                return Files.createTempFile(target.getParent(), ".agentcore-download-", ".tmp");
            }).subscribeOn(Schedulers.boundedElastic()), temporary -> download.apply(temporary).flatMap(size -> Mono.fromCallable(() -> {
                // Re-check links before committing a local file. No partial file becomes visible.
                local(output);
                if (overwrite) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, target);
                return Map.<String, Object>of("path", target.toString(), "bytes", size);
            }).subscribeOn(Schedulers.boundedElastic())), temporary -> Mono.fromRunnable(() -> {
                try { Files.deleteIfExists(temporary); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).subscribeOn(Schedulers.boundedElastic()));
        });
    }
    private Path local(String input) throws java.io.IOException {
        Path root = workspace.toRealPath();
        Path path = Path.of(input); path = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Local path must stay in the collaboration workspace");
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Local path must not contain symbolic links");
        }
        return path;
    }
    private static String remote(String path) {
        if (!(path.equals("shared") || path.startsWith("shared/")) || path.contains("\\")
            || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("Team file path must be under shared/");
        return path;
    }
    static Map<String, String> page(Map<String, Object> args, String key, String value) {
        var query = new LinkedHashMap<String, String>(); query.put(key, value);
        for (String name : List.of("cursor", "limit", "prefix")) if (args.get(name) != null) query.put(name, String.valueOf(args.get(name)));
        return query;
    }
    private static byte[] content(Map<String, Object> args) {
        String value = (String) args.get("content");
        return switch (String.valueOf(args.getOrDefault("encoding", "utf-8"))) {
            case "utf-8" -> value.getBytes(StandardCharsets.UTF_8);
            case "base64" -> Base64.getDecoder().decode(value);
            default -> throw new IllegalArgumentException("encoding must be utf-8 or base64");
        };
    }
    private static Map<String, Object> summary(String action, String path, List<Map<String, Object>> files) {
        return Map.of("action", action, "path", path, "transferred", files.size(), "files", files);
    }
}
