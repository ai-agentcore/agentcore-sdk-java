package io.agentcore.skill;

import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.controlplane.EndpointFallback;
import io.agentcore.AgentCoreException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Each materialization owns its directory, never a shared mutable content cache. */
public final class Skills implements AutoCloseable {
    private final Supplier<Mono<ControlPlane>> controlPlane;
    private final HttpTransport http;
    private final Path workspace;
    private final Map<Key, Mono<Skill>> pins = new java.util.HashMap<>();
    private record Key(String name, String version) {}
    private Path directory;
    private boolean closed;
    public Skills(Supplier<Mono<ControlPlane>> controlPlane, HttpTransport http) { this(controlPlane, http, null); }
    public Skills(Supplier<Mono<ControlPlane>> controlPlane, HttpTransport http, Path workspace) {
        this.controlPlane = controlPlane; this.http = http;
        this.workspace = workspace != null ? workspace : System.getenv("AGENTCORE_SKILL_WORKSPACE_DIR") == null ? null
            : Path.of(System.getenv("AGENTCORE_SKILL_WORKSPACE_DIR"));
    }
    public Mono<List<Skill>> local(Path root) {
        return Mono.fromCallable(() -> {
            if (Files.isRegularFile(root.resolve("SKILL.md"))) return List.of(Skill.read(null, null, root));
            try (var paths = Files.list(root)) {
                var result = new java.util.ArrayList<Skill>();
                for (var path : paths.filter(p -> Files.isRegularFile(p.resolve("SKILL.md"))).sorted().toList()) result.add(Skill.read(null, null, path));
                return List.copyOf(result);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    public Mono<Skill> load(String name) { return load(name, null); }
    public Mono<Skill> load(String name, String version) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (closed) return Mono.error(new IllegalStateException("Skills client is closed"));
                return pins.computeIfAbsent(new Key(name, version), key -> resolve(name, version).cacheInvalidateIf(value -> false));
            }
        });
    }
    private Mono<Skill> resolve(String name, String version) {
        return Mono.defer(controlPlane).flatMap(cp -> {
            String path = "/skills/" + ControlPlane.encode(name);
            Mono<String> selected = version != null ? Mono.just(version)
                : cp.request("GetSkillDetail", "GET", path, Map.of()).map(value -> latest(Json.object(value.get("data"))));
            return selected.flatMap(value -> cp.request("DownloadSkillVersionViaOss", "GET",
                path + "/versions/" + ControlPlane.encode(value) + "/actions/download-via-oss", Map.of())
                .flatMap(response -> download(URI.create(Json.text(response.get("data"), "Skill download URL"))))
                .flatMap(archive -> Mono.fromCallable(() -> materialize(name, value, archive)).subscribeOn(Schedulers.boundedElastic())));
        });
    }
    private Mono<byte[]> download(URI url) {
        return http.download(url).onErrorResume(error -> {
            URI fallback = EndpointFallback.oss(url);
            boolean unavailable = EndpointFallback.connectionFailure(error)
                || error instanceof AgentCoreException failure && failure.status() != null && failure.status() >= 500;
            if (fallback == null || !unavailable) return Mono.error(error);
            org.slf4j.LoggerFactory.getLogger(Skills.class).warn("agentcore.skill.download.fallback host={}", fallback.getHost());
            return http.download(fallback);
        });
    }
    private synchronized Skill materialize(String name, String version, byte[] archive) throws IOException {
        if (closed) throw new IllegalStateException("Skills client is closed");
        if (directory == null) directory = workspace == null ? Files.createTempDirectory("agentcore-skills-")
            : Files.createTempDirectory(Files.createDirectories(workspace), "agentcore-skills-");
        Path target = Files.createTempDirectory(directory, ControlPlane.encode(name) + "-");
        try {
            extract(archive, target);
            Path root = target;
            if (!Files.isRegularFile(root.resolve("SKILL.md"))) {
                try (var paths = Files.list(root)) {
                    var children = paths.filter(p -> !p.getFileName().toString().startsWith(".") && !p.getFileName().toString().equals("__MACOSX")).toList();
                    if (children.size() != 1 || !Files.isDirectory(children.get(0))) throw new IOException("Skill archive has no unambiguous root");
                    root = children.get(0);
                }
            }
            return Skill.read(name, version, root);
        } catch (Exception error) { deleteTree(target); throw error; }
    }
    public static void extract(byte[] bytes, Path target) throws IOException {
        if (bytes.length < 2 || bytes.length > 10 * 1024 * 1024) throw new IOException("Invalid Skill archive size");
        var input = new ByteArrayInputStream(bytes);
        try (ArchiveInputStream<?> archive = bytes[0] == 'P' && bytes[1] == 'K' ? new ZipArchiveInputStream(input)
                : new TarArchiveInputStream(bytes[0] == 31 && bytes[1] == (byte) 139 ? new GZIPInputStream(input) : input)) {
            ArchiveEntry entry; long remaining = 50L * 1024 * 1024;
            Path root = target.toAbsolutePath().normalize();
            while ((entry = archive.getNextEntry()) != null) {
                Path path = root.resolve(entry.getName()).normalize();
                if (!path.startsWith(root) || entry instanceof ZipArchiveEntry zip && zip.isUnixSymlink()
                    || entry instanceof TarArchiveEntry tar && !(tar.isFile() || tar.isDirectory()))
                    throw new IOException("Unsafe Skill archive entry");
                if (entry.isDirectory()) { Files.createDirectories(path); continue; }
                Files.createDirectories(path.getParent());
                try (var output = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192]; int size;
                    while ((size = archive.read(buffer)) != -1) {
                        remaining -= size;
                        if (remaining < 0) throw new IOException("Expanded Skill exceeds 50 MiB");
                        output.write(buffer, 0, size);
                    }
                }
            }
        }
    }
    private static String latest(Map<String, Object> data) {
        for (var label : Json.object(data.getOrDefault("labels", Map.of())).entrySet())
            if (label.getKey().equalsIgnoreCase("latest")) return Json.text(label.getValue(), "latest version");
        return ((List<?>) data.getOrDefault("versions", List.of())).stream().map(Json::object)
            .filter(v -> "online".equalsIgnoreCase(String.valueOf(v.get("status"))))
            .max(Comparator.comparingLong(v -> v.get("updateTime") instanceof Number n ? n.longValue() : -1))
            .map(v -> Json.text(v.get("version"), "version")).orElseThrow(() -> new IllegalArgumentException("Skill has no online version"));
    }
    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    @Override public synchronized void close() throws IOException { closed = true; pins.clear(); if (directory != null) { deleteTree(directory); directory = null; } }
}
