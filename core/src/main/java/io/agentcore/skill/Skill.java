package io.agentcore.skill;

import io.agentcore.Json;
import io.agentcore.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public record Skill(String name, String description, String version, Path root, String instruction) {
    /** One tool set for multiple Skills, with name-based dispatch and an explicit command directory. */
    public static List<Tool> tools(List<Skill> skills, Path workingDirectory) {
        return tools(skills, workingDirectory, null, 300);
    }
    public static List<Tool> tools(List<Skill> skills, Path workingDirectory,
            java.util.function.BiPredicate<String, Path> commandApproval, long commandTimeoutSeconds) {
        var selected = new java.util.LinkedHashMap<String, List<Tool>>();
        for (var skill : skills) if (selected.putIfAbsent(skill.name(), skill.tools(commandApproval, commandTimeoutSeconds)) != null)
            throw new IllegalArgumentException("Duplicate Skill name: " + skill.name());
        if (selected.isEmpty()) return List.of();
        var result = new ArrayList<Tool>();
        for (var prototype : selected.values().iterator().next()) {
            result.add(new Tool(prototype.name(), prototype.name().equals("execute_command") ? prototype.description()
                : prototype.name().equals("load_skills") ? "List Skills or load instructions by name: " + skills.stream().map(s -> s.name() + ": " + s.description()).toList() : "Read a file or list a directory within the named Skill",
                prototype.parameters(), args -> {
                    if (prototype.name().equals("execute_command")) {
                        var parameters = new java.util.LinkedHashMap<>(args);
                        parameters.putIfAbsent("cwd", workingDirectory.toAbsolutePath().toString());
                        return prototype.call(parameters);
                    }
                    if (prototype.name().equals("load_skills") && args.get("name") == null)
                        return Mono.just(Map.of("skills", skills.stream().map(s -> Map.of("name", s.name(), "description", s.description())).toList()));
                    var tools = selected.get(args.get("name"));
                    if (tools == null) return Mono.error(new IllegalArgumentException("Unknown Skill name"));
                    return tools.stream().filter(tool -> tool.name().equals(prototype.name())).findFirst().orElseThrow().call(args);
                }));
        }
        return List.copyOf(result);
    }
    public static Skill local(String name, Path root) throws IOException {
        return read(name, null, root);
    }
    static Skill read(String name, String version, Path root) throws IOException {
        Path directory = root.toRealPath();
        String instruction = Files.readString(directory.resolve("SKILL.md")); String description = "";
        var frontmatter = java.util.regex.Pattern.compile("(?s)^---[ \\t]*\\R(.*?)\\R---[ \\t]*(?:\\R|$)(.*)$").matcher(instruction);
        if (frontmatter.matches()) {
            var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            var metadata = Json.object(yaml.load(frontmatter.group(1)));
            if (name == null && metadata.get("name") instanceof String value) name = value;
            if (metadata.get("description") instanceof String value) description = value;
            instruction = frontmatter.group(2);
        }
        return new Skill(name == null ? directory.getFileName().toString() : name, description, version, directory, instruction);
    }
    public List<Tool> tools() {
        return tools(null, 300);
    }
    public List<Tool> tools(java.util.function.BiPredicate<String, Path> commandApproval, long commandTimeoutSeconds) {
        if (commandTimeoutSeconds <= 0) throw new IllegalArgumentException("command timeout must be positive");
        var tools = new ArrayList<Tool>();
        tools.add(new Tool("load_skills", "Load instructions for " + name + ": " + description,
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))), args -> Mono.fromCallable(() -> {
                if (args.get("name") == null) return Map.of("skills", List.of(Map.of("name", name, "description", description)));
                if (!name.equals(args.get("name"))) throw new IllegalArgumentException("Unknown skill name");
                try (var files = Files.list(root)) {
                    return Map.of("name", name, "description", description, "instruction", instruction, "directory", root.toString(), "files", files.map(p -> p.getFileName().toString()).sorted().toList());
                }
            }).cast(Object.class).subscribeOn(Schedulers.boundedElastic())));
        tools.add(new Tool("read_skill_file", "Read a file or list a directory within " + name,
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"), "relative_path", Map.of("type", "string")),
                "required", List.of("name", "relative_path")), args -> Mono.fromCallable(() -> {
                if (!name.equals(args.get("name"))) throw new IllegalArgumentException("Unknown skill name");
                Path path = root.resolve(Json.text(args.get("relative_path"), "relative_path")).toRealPath();
                if (!path.startsWith(root.toRealPath())) throw new IllegalArgumentException("Path is outside the skill directory");
                if (Files.isDirectory(path)) try (var files = Files.list(path)) {
                    return Map.of("files", files.map(p -> p.getFileName().toString()).sorted().toList());
                }
                return Map.of("content", Files.readString(path));
            }).cast(Object.class).subscribeOn(Schedulers.boundedElastic())));
        if (!"false".equalsIgnoreCase(System.getenv("ALLOW_EXECUTE_COMMAND"))) tools.add(new Tool("execute_command",
            "Execute a shell command in the Agent container. Show the command and obtain user approval before invoking.",
            Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string"), "cwd", Map.of("type", "string"),
                "timeout", Map.of("type", "integer")), "required", List.of("command")), args -> execute(args, commandApproval, commandTimeoutSeconds).cast(Object.class)));
        return List.copyOf(tools);
    }
    private Mono<Map<String, Object>> execute(Map<String, Object> args, java.util.function.BiPredicate<String, Path> approval, long defaultTimeout) {
        return Mono.fromCallable(() -> {
            String command = Json.text(args.get("command"), "command");
            Path cwd = (args.containsKey("cwd") ? Path.of(Json.text(args.get("cwd"), "cwd")) : root).toAbsolutePath().normalize();
            long seconds = args.get("timeout") instanceof Number n ? n.longValue() : defaultTimeout;
            if (seconds <= 0) throw new IllegalArgumentException("timeout must be positive");
            if (approval != null && !approval.test(command, cwd)) return Map.<String, Object>of("error", "Command execution rejected by user");
            Path stdout = Files.createTempFile("agentcore-command-", ".out");
            Path stderr = Files.createTempFile("agentcore-command-", ".err");
            Process child = null;
            try {
                child = new ProcessBuilder("/bin/sh", "-c", command).directory(cwd.toFile())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
                boolean completed = child.waitFor(seconds, TimeUnit.SECONDS);
                if (!completed) { child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); child.waitFor(); }
                return Map.<String, Object>of("stdout", limitedText(stdout), "stderr", limitedText(stderr),
                    "exit_code", child.exitValue(), "timed_out", !completed);
            } finally {
                if (child != null && child.isAlive()) { child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); }
                Files.deleteIfExists(stdout); Files.deleteIfExists(stderr);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private static String limitedText(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) { return new String(input.readNBytes(100 * 1024), java.nio.charset.StandardCharsets.UTF_8); }
    }
}
