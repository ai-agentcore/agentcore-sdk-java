package io.agentcore.collaboration;

import io.agentcore.Json;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class TeamsProvider {
    public record Snapshot(String runtimeName, String selfName, String tokenEnv, List<Map<String, Object>> teams) {
        public Snapshot { teams = List.copyOf(teams); }
        public boolean workerIn(String teamId) {
            return teams.stream().anyMatch(t -> (teamId == null || teamId.equals(t.get("name")))
                && "worker".equals(Json.object(t.get("membership")).get("role")));
        }
    }
    private final Path path;
    private final java.util.function.Supplier<Mono<String>> source;
    private String previous;
    private Snapshot lastGood;
    public TeamsProvider(Path path) { this.path = path; this.source = null; }
    public TeamsProvider(java.util.function.Supplier<Mono<String>> source) {
        this.path = null;
        var cached = Mono.defer(source).cache(java.time.Duration.ofSeconds(60));
        this.source = () -> cached;
    }
    public Mono<Snapshot> snapshot() {
        if (source == null) return Mono.fromCallable(this::read).subscribeOn(Schedulers.boundedElastic());
        return Mono.defer(source).flatMap(text -> Mono.fromCallable(() -> accept(text)))
            .switchIfEmpty(Mono.defer(() -> { synchronized (this) { lastGood = null; previous = null; } return Mono.empty(); }))
            .onErrorResume(error -> {
                synchronized (this) {
                    if (lastGood == null) return Mono.error(error);
                    logIgnored("bootstrap", error);
                    return Mono.just(lastGood);
                }
            });
    }
    private synchronized Snapshot accept(String text) {
        if (text.equals(previous)) return lastGood;
        Snapshot candidate = parse(text); lastGood = candidate; previous = text; return candidate;
    }
    private synchronized Snapshot read() throws Exception {
        try {
            String text = Files.readString(path);
            return accept(text);
        } catch (NoSuchFileException error) { previous = null; lastGood = null; return null; }
        catch (Exception error) {
            if (lastGood == null) throw error;
            logIgnored(path.toString(), error);
            return lastGood;
        }
    }
    public static Snapshot parse(String text) {
        Map<String, Object> root;
        try { var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); root = Json.object(new Yaml(new SafeConstructor(options)).load(text)); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid teams.yaml"); }
        if (!"TeamsConfig".equals(root.get("kind")) || !"agentteams.io/v1alpha1".equals(root.get("apiVersion"))) throw new IllegalArgumentException("Unsupported teams.yaml kind or apiVersion");
        var spec = Json.object(root.get("spec")); var self = Json.object(spec.get("self"));
        String runtime = Json.text(Json.object(root.get("metadata")).get("runtimeName"), "runtimeName");
        if (!runtime.equals(self.get("runtimeName"))) throw new IllegalArgumentException("Mismatched runtimeName in teams.yaml");
        String tokenEnv = spec.get("matrix") == null ? null : Json.text(Json.object(spec.get("matrix")).get("tokenEnv"), "matrix.tokenEnv");
        if (tokenEnv != null) Json.text(self.get("matrixUserId"), "self.matrixUserId");
        if (!(spec.get("teams") instanceof List<?> teams)) throw new IllegalArgumentException("teams must be an array");
        var values = teams.stream().map(Json::object).map(Map::copyOf).toList();
        var names = new java.util.HashSet<String>();
        for (var team : values) {
            if (!names.add(Json.text(team.get("name"), "team.name"))) throw new IllegalArgumentException("Duplicate team name");
            Json.text(Json.object(team.get("membership")).get("role"), "membership.role");
        }
        return new Snapshot(runtime, Json.text(self.get("name"), "self.name"), tokenEnv, values);
    }
    private static void logIgnored(String source, Throwable error) {
        // Parse errors contain our validation messages, not the YAML document.
        String reason = error instanceof io.agentcore.AgentCoreException || error instanceof IllegalArgumentException
            ? error.getMessage() : "Configuration source could not be read";
        LoggerFactory.getLogger(TeamsProvider.class).warn(
            "agentcore.collaboration.teams.update_ignored source={} error_type={} reason={}",
            source, error.getClass().getSimpleName(), reason);
    }
}
