package io.agentcore.template;

import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.controlplane.ArchiveDownload;
import io.agentcore.skill.Skill;
import io.agentcore.skill.Skills;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** AgentSpec configuration only; graph construction and model choice remain application-owned. */
public final class Templates implements AutoCloseable {
    private final Supplier<Mono<ControlPlane>> controlPlane;
    private final HttpTransport http;
    private final Skills skills;
    private final Path workspace;
    private Path directory;
    private boolean closed;
    private record Definition(String name, String source, String selectorType, String selectorValue) {}

    public Templates(Supplier<Mono<ControlPlane>> controlPlane, HttpTransport http, Skills skills, Path workspace) {
        this.controlPlane = controlPlane; this.http = http; this.skills = skills;
        this.workspace = workspace != null ? workspace : System.getenv("AGENTCORE_SKILL_WORKSPACE_DIR") == null ? null
            : Path.of(System.getenv("AGENTCORE_SKILL_WORKSPACE_DIR"));
    }
    public Mono<AgentTemplate> get(String name) { return get(name, null); }
    public Mono<AgentTemplate> get(String name, String version) {
        return Mono.defer(controlPlane).flatMap(cp -> {
            String path = "/agent-specs/" + ControlPlane.encode(component(name));
            Mono<String> selected = version != null ? Mono.just(component(version))
                : cp.request("GetAgentSpec", "GET", path, Map.of()).map(response ->
                    Json.text(Json.object(Json.object(response.get("data")).getOrDefault("labels", Map.of())).get("latest"), "Template latest version"));
            return selected.flatMap(value -> cp.request("DownloadAgentSpecViaOss", "GET", path + "/actions/download-via-oss", Map.of("agentSpecVersion", value))
                .flatMap(response -> ArchiveDownload.get(http, URI.create(Json.text(response.get("data"), "Template download URL")), 50 * 1024 * 1024))
                .flatMap(bytes -> Mono.fromCallable(() -> parse(name, value, bytes, cp)).subscribeOn(Schedulers.boundedElastic())));
        }).doOnSuccess(value -> org.slf4j.LoggerFactory.getLogger(Templates.class).info("agentcore.template.get.succeeded name={} version={}", name, value.version()))
            .doOnError(error -> org.slf4j.LoggerFactory.getLogger(Templates.class).warn("agentcore.template.get.failed name={} version={}", name, version, error));
    }
    private AgentTemplate parse(String name, String version, byte[] bytes, ControlPlane cp) throws IOException {
        Path root = extract(bytes);
        Map<String, Object> manifest = Files.isRegularFile(root.resolve("manifest.json"))
            ? Json.read(Files.readString(root.resolve("manifest.json"))) : Map.of();
        var definitions = definitions(manifest, root);
        String agents = read(root.resolve("config/AGENTS.md")), soul = read(root.resolve("config/SOUL.md"));
        String instruction = !agents.isBlank() && !soul.isBlank() ? "## AGENTS.md\n\n" + agents.strip() + "\n\n## SOUL.md\n\n" + soul.strip()
            : !agents.isBlank() ? agents : soul;
        Mono<List<Skill>> loader = Mono.defer(controlPlane).flatMap(ignored -> Flux.fromIterable(definitions).concatMap(item -> {
            org.slf4j.LoggerFactory.getLogger(Templates.class).info("agentcore.template.skill.load.started template={} skill={} source={}", name, item.name(), item.source());
            if (item.source().equals("REFERENCE")) return resolveVersion(cp, item).flatMap(value -> skills.load(item.name(), value));
            return Mono.fromCallable(() -> Skill.local(item.name(), root.resolve("skills").resolve(item.name()))).subscribeOn(Schedulers.boundedElastic());
        }).collectList());
        return new AgentTemplate(name, version, instruction, loader);
    }
    private Mono<String> resolveVersion(ControlPlane cp, Definition item) {
        if (item.selectorType().equals("VERSION")) return Mono.just(item.selectorValue());
        return cp.request("GetSkillDetail", "GET", "/skills/" + ControlPlane.encode(item.name()), Map.of()).map(response -> {
            var data = Json.object(response.get("data"));
            if (item.selectorValue().equals("HEAD") && ((List<?>) data.getOrDefault("versions", List.of())).stream()
                    .map(Json::object).anyMatch(value -> "HEAD".equals(value.get("version")))) return "HEAD";
            return Json.text(Json.object(data.getOrDefault("labels", Map.of())).get(item.selectorValue()), "Skill " + item.name() + " label " + item.selectorValue());
        });
    }
    private static List<Definition> definitions(Map<String, Object> manifest, Path root) throws IOException {
        if (!(manifest.getOrDefault("skills", List.of()) instanceof List<?> values)) throw new IllegalArgumentException("Template skills must be an array");
        var result = new LinkedHashMap<String, Definition>();
        for (var value : values) {
            var item = value instanceof String s ? Map.<String, Object>of("name", s) : Json.object(value);
            String name = component(Json.text(item.get("name"), "Skill name"));
            String source = item.get("sourceType") == null ? "STATIC" : String.valueOf(item.get("sourceType"));
            if (item.get("sourceType") != null && item.get("version") != null) throw new IllegalArgumentException("Template Skill must use versionSelector, not version: " + name);
            if (!source.equals("STATIC") && !source.equals("REFERENCE") || source.equals("STATIC") && item.get("versionSelector") != null)
                throw new IllegalArgumentException("Invalid template Skill source or selector: " + name);
            var selector = item.get("versionSelector") == null ? Map.<String, Object>of("type", "LABEL", "value", "latest") : Json.object(item.get("versionSelector"));
            String type = Json.text(selector.get("type"), "selector type"), selected = Json.text(selector.get("value"), "selector value");
            if (!type.equals("LABEL") && !type.equals("VERSION")) throw new IllegalArgumentException("Invalid Skill selector: " + name);
            if (result.putIfAbsent(name, new Definition(name, source, type, selected)) != null) throw new IllegalArgumentException("Duplicate template Skill: " + name);
        }
        Path skillRoot = root.resolve("skills");
        if (Files.isDirectory(skillRoot)) try (var paths = Files.list(skillRoot)) {
            for (var path : paths.sorted().toList()) if (Files.isRegularFile(path.resolve("SKILL.md"))) {
                String name = component(path.getFileName().toString());
                if (result.containsKey(name) && result.get(name).source().equals("REFERENCE")) throw new IllegalArgumentException("Template Skill is both a reference and a file: " + name);
                result.putIfAbsent(name, new Definition(name, "STATIC", "LABEL", "latest"));
            }
        }
        return List.copyOf(result.values());
    }
    private synchronized Path extract(byte[] bytes) throws IOException {
        if (closed) throw new IllegalStateException("Templates client is closed");
        if (directory == null) directory = workspace == null ? Files.createTempDirectory("agentcore-templates-")
            : Files.createTempDirectory(Files.createDirectories(workspace), "agentcore-templates-");
        Path target = Files.createTempDirectory(directory, "template-");
        try { Skills.extract(bytes, target, 50 * 1024 * 1024); return target; }
        catch (Exception error) { deleteTree(target); throw error; }
    }
    private static String component(String value) {
        Json.text(value, "Template resource name or version");
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid template resource name or version");
        return value;
    }
    private static String read(Path path) throws IOException { return Files.isRegularFile(path) ? Files.readString(path) : ""; }
    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    @Override public synchronized void close() throws IOException { closed = true; if (directory != null) { deleteTree(directory); directory = null; } }
}
