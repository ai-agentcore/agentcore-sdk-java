package io.agentcore.collaboration;

import io.agentcore.Json;
import io.agentcore.runtime.RuntimeEnvironment;
import io.agentcore.tool.Tool;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Optional Worker tooling, usable with or without AgentCoreServer. No Matrix polling or agent loop. */
public final class Collaboration implements AutoCloseable {
    public static final String WORKER_PROMPT = """
        ## Team collaboration capabilities

        You can use the team collaboration capabilities described below. These rules apply to collaboration
        operations and do not change the application's identity or the current user request.

        Use task-execution for incoming task assignments and revision notifications. Use team and task
        queries when the current request needs that information. Handle other requests through the
        application's normal workflow; available collaboration tools are not an instruction to look for
        work.
        Explain a tool failure only when it affects the current request, and never infer a business result
        from a failed query.

        For assigned Subtask work, execute only the Subtask assigned to you. Do not create or manage
        top-level Tasks, reassign Subtasks, or approve your own Results. Treat Task Service state and
        authorization as authoritative.

        At the start of every newly assigned Subtask or revision turn, you MUST load and follow
        `task-execution`, including its Task/Subtask file workflow. Before synchronizing non-Task Team
        files, load and follow `file-sharing`. If a required Skill is unavailable, do not perform
        collaboration writes.

        For assigned Subtask work, produce only the requested deliverables and perform only explicitly
        required checks or checks necessary to establish usability. Once they pass, submit a short Result.
        After submission succeeds, reply with one short sentence that it awaits review; do not restate the
        Result, deliverables, checks, or identifiers. Disclose material limitations in the Result.

        When configured Team membership, roles, or Matrix identities matter, call
        `agentteams_get_team_context`; do not infer them from messages. It returns the configured Runtime
        roster, not live Matrix room membership.

        Do not inspect or expose credentials, tokens, authorization headers, signed URLs, or routing
        metadata. Never include them in prompts, messages, Task state, Results, Events, or uploaded files.
        """.strip();
    private final TeamsProvider teams;
    private final TaskServiceClient tasks;
    private final CollaborationFiles files;
    private final Path workspace;
    private Path skillDirectory;
    private List<io.agentcore.skill.Skill> builtInSkills;
    private boolean closed;
    public Collaboration(TeamsProvider teams, TaskServiceClient tasks) { this(teams, tasks, Path.of(".")); }
    public Collaboration(TeamsProvider teams, TaskServiceClient tasks, Path workspace) {
        this.teams = teams; this.tasks = tasks; this.workspace = workspace.toAbsolutePath().normalize();
        this.files = new CollaborationFiles(tasks, this.workspace);
    }
    public static Collaboration auto() {
        return auto(Path.of("."));
    }
    public static Collaboration auto(Path workspace) {
        var teams = new TeamsProvider(Path.of(System.getenv().getOrDefault("AGENTCORE_TEAMS_PATH", "/var/run/agentcore/agent/teams.yaml")));
        var client = new TaskServiceClient(() -> Mono.fromCallable(() -> {
            var path = Path.of(System.getenv().getOrDefault("AGENTCORE_ENV_PATH", "/var/run/agentcore/agent/env"));
            var values = new LinkedHashMap<>(System.getenv());
            try { values.putAll(RuntimeEnvironment.variables(Files.readString(path))); }
            catch (NoSuchFileException ignored) { /* No mounted env file: use process environment. */ }
            return Map.copyOf(values);
        }).subscribeOn(Schedulers.boundedElastic()));
        return new Collaboration(teams, client, workspace);
    }
    /** Reuse the Core-owned bootstrap lifecycle; closing Collaboration does not close Core. */
    public static Collaboration auto(io.agentcore.AgentCore core, Path workspace) {
        var runtime = core.bootstrapRuntime();
        if (runtime == null) return auto(workspace);
        return new Collaboration(new TeamsProvider(runtime::teamsConfig),
            new TaskServiceClient(() -> Mono.just(Map.of("AGENTCORE_MATRIX_URL", runtime.matrixUrl().toString())), runtime::matrixToken), workspace);
    }
    /** Append collaboration instructions without replacing the application's identity or querying team state. */
    public String composePrompt(String userPrompt) {
        String collaborationPrompt = WORKER_PROMPT + "\n\n## Collaboration Workspace\n\n"
            + "The local collaboration workspace is `" + workspace + "`. Pass `local_path` and "
            + "`output_path` relative to this directory unless an absolute path is already known. "
            + "Before uploading an artifact created elsewhere, stage it in this workspace using "
            + "only filesystem capabilities already provided by the application.";
        return userPrompt.isBlank() ? collaborationPrompt : userPrompt.stripTrailing() + "\n\n" + collaborationPrompt;
    }
    /** Reusable tools; request context and team membership are resolved at invocation time. */
    public Mono<List<Tool>> tools() {
        return Mono.fromSupplier(() -> {
            checkOpen();
            var result = new ArrayList<Tool>();
            add(result, "get_team_context", Map.of(), List.of(), false,
                (s, args, context) -> Mono.just(Map.of("self", Map.of("name", s.selfName(), "runtimeName", s.runtimeName()), "teams", s.teams())));
            add(result, "list_tasks", paging(fields("status", "team_id", "assigned_to", "search", "cursor")), List.of(), false,
                (s, args, context) -> tasks.request(s, "GET", "/v1/tasks", query(args, Map.of("status", "status", "team_id", "teamId", "assigned_to", "assignedTo", "search", "search", "cursor", "cursor", "limit", "limit")), null, null));
            add(result, "list_subtasks", fields("task_id", "status", "assigned_to"), List.of(), false,
                (s, args, context) -> tasks.request(s, "GET", "/v1/sub-tasks", query(args, Map.of("task_id", "taskId", "status", "status", "assigned_to", "assignedTo")), null, null));
            add(result, "get_task", fields("task_id"), List.of("task_id"), false,
                (s, args, context) -> tasks.request(s, "GET", "/v1/tasks/" + id(args, "task_id"), Map.of(), null, null));
            add(result, "get_subtask", fields("subtask_id"), List.of("subtask_id"), false,
                (s, args, context) -> tasks.request(s, "GET", "/v1/sub-tasks/" + id(args, "subtask_id"), Map.of(), null, null));
            for (String operation : List.of("ack", "heartbeat", "block")) {
                var parameters = fields("subtask_id"); if (operation.equals("block")) { parameters.put("reason", Map.of("type", "string")); parameters.put("evidence", Map.of("type", "object")); }
                add(result, operation + "_subtask", parameters, operation.equals("block") ? List.of("subtask_id", "reason") : List.of("subtask_id"), true,
                    (s, args, context) -> {
                        var body = new LinkedHashMap<String, Object>();
                        if (!operation.equals("heartbeat")) body.put("relatedRoomMessageId", context.eventId());
                        if (operation.equals("block")) body.put("reason", Json.text(args.get("reason"), "reason"));
                        if (operation.equals("block") && args.get("evidence") != null) body.put("evidence", Json.object(args.get("evidence")));
                        String id = id(args, "subtask_id");
                        return tasks.request(s, "POST", "/v1/sub-tasks/" + id + "/" + operation, Map.of(), body,
                            operation.equals("heartbeat") ? null : TaskServiceClient.idempotencyKey(operation, id, context.eventId(), body));
                    });
            }
            var submission = fields("subtask_id", "summary"); submission.put("file_refs", Map.of("type", "array", "items", Map.of("type", "string")));
            add(result, "submit_subtask_result", submission, List.of("subtask_id", "summary"), true, (s, args, context) -> {
                var body = Map.<String, Object>of("summary", Json.text(args.get("summary"), "summary"), "fileRefs", args.getOrDefault("file_refs", List.of()), "relatedRoomMessageId", context.eventId());
                String id = id(args, "subtask_id");
                return tasks.request(s, "POST", "/v1/sub-tasks/" + id + "/results", Map.of(), body, TaskServiceClient.idempotencyKey("result", id, context.eventId(), body));
            });
            var progress = fields("subtask_id"); progress.put("content", Map.of("type", "object"));
            add(result, "report_subtask_progress", progress, List.of("subtask_id", "content"), true, (s, args, context) ->
                tasks.request(s, "POST", "/v1/sub-tasks/" + id(args, "subtask_id") + "/progress", Map.of(),
                    Map.of("content", Json.object(args.get("content")), "relatedRoomMessageId", context.eventId()), null));
            for (String target : List.of("task", "subtask")) {
                String key = target + "_id";
                String root = "/v1/" + (target.equals("task") ? "tasks" : "sub-tasks") + "/";
                add(result, "list_" + target + "_files", paging(fields(key, "prefix", "cursor")), List.of(key), false,
                    (s, args, context) -> tasks.request(s, "GET", root + id(args, key) + "/files", query(args, Map.of("prefix", "prefix", "cursor", "cursor", "limit", "limit")), null, null));
                add(result, "read_" + target + "_file", fields(key, "file_ref"), List.of(key, "file_ref"), false,
                    (s, args, context) -> files.read(s, root + id(args, key) + "/files", Json.text(args.get("file_ref"), "file_ref")));
                var parameters = fields(key, "file_ref", "output_path"); parameters.put("overwrite", Map.of("type", "boolean"));
                add(result, "download_" + target + "_file", parameters, List.of(key, "file_ref", "output_path"), false,
                    (s, args, context) -> files.download(s, root + id(args, key) + "/files", args));
            }
            var inline = fields("subtask_id", "path", "content", "content_type"); inline.put("encoding", Map.of("type", "string", "enum", List.of("utf-8", "base64")));
            add(result, "write_subtask_file", inline, List.of("subtask_id", "path", "content"), true, (s, args, context) -> files.upload(s, args, false));
            add(result, "write_subtask_file_from_path", fields("subtask_id", "path", "local_path", "content_type"), List.of("subtask_id", "path", "local_path"), true,
                (s, args, context) -> files.upload(s, args, true));
            add(result, "list_results", fields("task_id", "subtask_id"), List.of("task_id"), false,
                (s, args, context) -> tasks.request(s, "GET", "/v1/tasks/" + id(args, "task_id") + "/results", query(args, Map.of("subtask_id", "subTaskId")), null, null));
            add(result, "list_task_events", paging(fields("task_id", "subtask_id", "event_type", "cursor")), List.of("task_id"), false,
                (s, args, context) -> tasks.request(s, "GET", "/v1/tasks/" + id(args, "task_id") + "/events", query(args, Map.of("subtask_id", "subTaskId", "event_type", "type", "cursor", "cursor", "limit", "limit")), null, null));
            for (String operation : List.of("push", "pull", "list", "stat")) {
                var parameters = fields("path"); boolean transfer = operation.equals("push") || operation.equals("pull");
                if (transfer) parameters.put("local_path", Map.of("type", "string"));
                if (operation.equals("pull")) parameters.put("overwrite", Map.of("type", "boolean"));
                if (operation.equals("list")) { parameters.put("cursor", Map.of("type", "string")); paging(parameters); }
                add(result, "filesync_" + operation, parameters, transfer ? List.of("path", "local_path") : List.of("path"), false,
                    (s, args, context) -> files.sync(s, team(s, context), operation, args));
            }
            return List.copyOf(result);
        });
    }
    private void add(List<Tool> tools, String name, Map<String, Object> properties, List<String> required,
            boolean write, CollaborationCall call) {
        tools.add(new Tool("agentteams_" + name, name.replace('_', ' ') + " using authoritative Task Service state",
            Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false), (args, metadata) -> {
                var context = (CollaborationContext) metadata.get(CollaborationContext.KEY);
                return Mono.defer(() -> { checkOpen(); return teams.snapshot(); }).switchIfEmpty(Mono.error(new ToolFailure("COLLABORATION_DISABLED", "Team membership is unavailable"))).flatMap(snapshot -> {
                    String teamId = context == null ? null : context.teamId();
                    if (!snapshot.workerIn(teamId)) {
                        boolean member = snapshot.teams().stream().anyMatch(team -> teamId == null || teamId.equals(team.get("name")));
                        return Mono.error(new ToolFailure(member ? "COLLABORATION_ROLE_UNSUPPORTED" : "COLLABORATION_TEAM_UNAVAILABLE",
                            member ? "The current Agent is not a Worker in this Team" : "Team membership is unavailable"));
                    }
                    if (!write) return call.apply(snapshot, args, context);
                    if (context == null)
                        return Mono.error(new ToolFailure("COLLABORATION_CONTEXT_REQUIRED", "This operation requires a collaboration Task Room"));
                    if (!"task".equals(context.roomKind()) || context.teamId() == null)
                        return Mono.error(new ToolFailure("COLLABORATION_TASK_UNAUTHORIZED", "This operation requires a collaboration Task Room"));
                    return tasks.request(snapshot, "GET", "/v1/sub-tasks/" + id(args, "subtask_id"), Map.of(), null, null)
                        .flatMap(subtask -> tasks.request(snapshot, "GET", "/v1/tasks/" + id(subtask, "taskId"), Map.of(), null, null))
                        .flatMap(task -> context.roomId().equals(task.get("roomId")) ? call.apply(snapshot, args, context)
                            : Mono.error(new ToolFailure("COLLABORATION_TASK_UNAUTHORIZED", "The Subtask belongs to another Task Room")));
                }).map(data -> (Object) Map.of("ok", true, "data", data))
                    .onErrorResume(ToolFailure.class, error -> Mono.just(Map.of(
                        "ok", false, "code", error.code, "retryable", false, "message", error.getMessage())))
                    .onErrorResume(io.agentcore.AgentCoreException.class, error ->
                        error.operation().startsWith("Task Service") ? Mono.just(TaskServiceClient.errorResult(error)) : Mono.error(error));
            }));
    }
    /** Expected collaboration precondition failures, not programming or infrastructure errors. */
    private static final class ToolFailure extends RuntimeException {
        private final String code;
        private ToolFailure(String code, String message) { super(message); this.code = code; }
    }
    @FunctionalInterface
    private interface CollaborationCall {
        Mono<Map<String, Object>> apply(TeamsProvider.Snapshot snapshot, Map<String, Object> arguments, CollaborationContext context);
    }
    private static LinkedHashMap<String, Object> fields(String... names) {
        var result = new LinkedHashMap<String, Object>(); for (String name : names) result.put(name, Map.of("type", "string")); return result;
    }
    private static String id(Map<String, Object> args, String key) { return TaskServiceClient.id(Json.text(args.get(key), key)); }
    private static Map<String, String> query(Map<String, Object> args, Map<String, String> keys) {
        var result = new LinkedHashMap<String, String>(); keys.forEach((from, to) -> { if (args.get(from) != null) result.put(to, String.valueOf(args.get(from))); }); return result;
    }
    private static LinkedHashMap<String, Object> paging(LinkedHashMap<String, Object> fields) {
        fields.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)); return fields;
    }
    private static String team(TeamsProvider.Snapshot snapshot, CollaborationContext context) {
        if (context == null) throw new ToolFailure("COLLABORATION_CONTEXT_REQUIRED", "Team file operations require a collaboration request context");
        if (context.teamId() != null) return context.teamId();
        var workers = snapshot.teams().stream().filter(t -> "worker".equals(Json.object(t.get("membership")).get("role"))).toList();
        if (workers.size() != 1) throw new ToolFailure("COLLABORATION_CONTEXT_REQUIRED", "Cannot select a unique Team");
        return Json.text(workers.get(0).get("name"), "team.name");
    }
    public Mono<List<io.agentcore.skill.Skill>> skills() {
        return Mono.fromCallable(this::loadSkills).subscribeOn(Schedulers.boundedElastic());
    }
    private synchronized List<io.agentcore.skill.Skill> loadSkills() throws java.io.IOException {
        checkOpen();
        if (builtInSkills != null) return builtInSkills;
        if (skillDirectory == null) skillDirectory = Files.createTempDirectory("agentcore-worker-skills-");
        var result = new ArrayList<io.agentcore.skill.Skill>();
        for (String name : List.of("task-execution", "file-sharing")) {
            Path root = Files.createDirectories(skillDirectory.resolve(name));
            try (var input = Collaboration.class.getResourceAsStream("/worker-skills/" + name + "/SKILL.md")) {
                if (input == null) throw new java.io.IOException("Missing packaged Worker Skill: " + name);
                Files.copy(input, root.resolve("SKILL.md"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            result.add(io.agentcore.skill.Skill.local(name, root));
        }
        builtInSkills = List.copyOf(result); return builtInSkills;
    }
    private synchronized void checkOpen() { if (closed) throw new IllegalStateException("Collaboration client is closed"); }
    @Override public synchronized void close() throws java.io.IOException {
        closed = true;
        if (skillDirectory != null) try (var paths = Files.walk(skillDirectory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        skillDirectory = null; builtInSkills = null;
    }
}
