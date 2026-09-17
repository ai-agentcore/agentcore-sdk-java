package io.agentcore;

import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.auth.ControllerCredentials;
import io.agentcore.auth.CredentialProvider;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.memory.MemoryStore;
import io.agentcore.mcp.MCPClient;
import io.agentcore.model.ModelClient;
import io.agentcore.runtime.AgentConfig;
import io.agentcore.runtime.RuntimeEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Lazy runtime discovery; direct clients do not read agent.yaml or contact the control plane. */
public final class AgentCore implements AutoCloseable {
    private final HttpTransport http = new HttpTransport();
    private final io.agentcore.skill.Skills skills;
    private final List<MCPClient> mcps = new ArrayList<>();
    private final Mono<AgentConfig> config;
    private final Mono<ControllerCredentials> controller;
    private final Mono<ControlPlane> controlPlane;
    private final io.agentcore.runtime.BootstrapRuntime bootstrap;
    private boolean closed;

    private AgentCore(Builder options) {
        skills = new io.agentcore.skill.Skills(this::controlPlane, http, options.skillWorkspaceDir);
        Path configPath = options.configPath != null ? options.configPath
            : Path.of(System.getenv().getOrDefault("AGENTCORE_CONFIG_PATH", "/var/run/agentcore/agent/agent.yaml"));
        Path envPath = options.envPath != null ? options.envPath
            : Path.of(System.getenv().getOrDefault("AGENTCORE_ENV_PATH", "/var/run/agentcore/agent/env"));
        String workspaceId = options.workspaceId;
        String regionId = options.regionId;
        AccessKeyCredential credential = options.credential;
        URI explicitControlEndpoint = options.controlEndpoint;
        URI controlEndpoint = explicitControlEndpoint != null ? explicitControlEndpoint
            : System.getenv("AGENTCORE_CONTROL_ENDPOINT") == null ? null : AgentConfig.httpUrl(System.getenv("AGENTCORE_CONTROL_ENDPOINT"));
        String rawToken = options.bootstrapToken;
        bootstrap = rawToken == null || rawToken.isBlank() ? null
            : new io.agentcore.runtime.BootstrapRuntime(io.agentcore.runtime.BootstrapToken.parse(rawToken), http);
        config = workspaceId != null ? Mono.error(new IllegalStateException("AgentCore runtime configuration is unavailable in explicit Workspace mode"))
            : bootstrap != null ? bootstrap.config() : Mono.fromCallable(() -> AgentConfig.parse(Files.readString(configPath)))
            .subscribeOn(Schedulers.boundedElastic()).retryWhen(Retry.fixedDelay(options.waitSeconds * 2, Duration.ofMillis(500))
                .filter(error -> error instanceof NoSuchFileException).onRetryExhaustedThrow((spec, signal) -> signal.failure()))
            .cacheInvalidateIf(value -> false);
        Mono<Map<String, String>> environment = Mono.fromCallable(() -> {
            String content;
            try { content = Files.readString(envPath); }
            catch (NoSuchFileException missing) {
                var process = System.getenv();
                if (credential == null && (!(process.containsKey("AGENTCORE_CONTROLLER_URL") || process.containsKey("AGENTTEAMS_CONTROLLER_URL"))
                    || !(process.containsKey("AGENTCORE_AUTH_TOKEN_FILE") || process.containsKey("AGENTTEAMS_AUTH_TOKEN_FILE")))) throw missing;
                content = "";
            }
            var values = new java.util.LinkedHashMap<>(System.getenv());
            values.putAll(RuntimeEnvironment.variables(content));
            return (Map<String, String>) Map.copyOf(values);
        }).subscribeOn(Schedulers.boundedElastic())
            .retryWhen(Retry.fixedDelay(options.waitSeconds * 2, Duration.ofMillis(500))
                .filter(error -> error instanceof NoSuchFileException).onRetryExhaustedThrow((spec, signal) -> signal.failure()))
            .cacheInvalidateIf(value -> false);
        controller = workspaceId != null ? Mono.error(new IllegalStateException("Runtime credentials are unavailable in explicit Workspace mode; use accessKeyCredential for control-plane access"))
            : bootstrap != null ? Mono.just(bootstrap.controller()) : environment.map(values -> {
            var env = RuntimeEnvironment.parse("", values);
            return new ControllerCredentials(env.controllerUrl(), env.tokenFile(), http);
        }).cacheInvalidateIf(value -> false);
        controlPlane = Mono.defer(() -> {
            CredentialProvider provider = credential != null ? purpose -> Mono.just(credential)
                : purpose -> controller.flatMap(value -> value.get(purpose));
            if (workspaceId != null && regionId != null)
                return Mono.just(new ControlPlane(workspaceId, regionId, controlEndpoint, provider));
            return config.flatMap(value -> {
                if (bootstrap != null) return Mono.just(new ControlPlane(value.workspaceId(), value.regionId(), controlEndpoint, provider));
                return environment.map(env -> new ControlPlane(value.workspaceId(), value.regionId(),
                    explicitControlEndpoint != null ? explicitControlEndpoint : env.get("AGENTCORE_CONTROL_ENDPOINT") == null
                        ? controlEndpoint : AgentConfig.httpUrl(env.get("AGENTCORE_CONTROL_ENDPOINT")), provider));
            });
        }).cacheInvalidateIf(value -> false);
    }
    public static AgentCore auto() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    public Mono<ControlPlane> controlPlane() { return Mono.defer(() -> { checkOpen(); return controlPlane; }); }
    public Mono<ModelClient> model(String connectionName, String modelName) {
        return controlPlane().flatMap(cp -> cp.list("ListModelConnections", "/model-connections",
            Map.of("name", connectionName, "searchType", "accurate")).flatMap(items -> {
                var connection = ControlPlane.exact(items, "name", connectionName);
                String id = Json.text(connection.get("connectionId"), "connectionId");
                String protocol = Json.text(connection.get("protocol"), "protocol");
                return cp.list("ListModels", "/models", modelName == null ? Map.of("connectionId", id)
                    : Map.of("connectionId", id, "modelName", modelName)).flatMap(models -> {
                        if (modelName == null && models.size() != 1)
                            return Mono.error(new IllegalArgumentException("Select a model name for this connection"));
                        var model = modelName == null ? models.get(0) : ControlPlane.exact(models, "modelName", modelName);
                        var wireProtocol = switch (protocol.toLowerCase(java.util.Locale.ROOT)) {
                            case "openai/v1" -> ModelClient.Protocol.OPENAI;
                            case "anthropic", "anthropic/v1" -> ModelClient.Protocol.ANTHROPIC;
                            default -> throw new IllegalArgumentException("Unsupported model protocol: " + protocol);
                        };
                        var capabilities = new java.util.LinkedHashMap<String, Boolean>();
                        if (model.get("capabilities") instanceof Map<?, ?> entries) entries.forEach((key, enabled) -> {
                            if (key instanceof String s && enabled instanceof Boolean b) capabilities.put(s, b);
                        });
                        var descriptor = new ModelClient.Descriptor(id, connectionName, protocol,
                            (String) connection.getOrDefault("providerType", ""), (String) model.get("modelId"),
                            Json.text(model.get("modelName"), "modelName"),
                            model.get("contextSize") instanceof Number n ? n.intValue() : null,
                            model.get("maxTokens") instanceof Number n ? n.intValue() : null, capabilities);
                        return config.map(value -> new ModelClient(new ModelClient.Settings(
                            Json.text(model.get("modelName"), "modelName"), ModelClient.managedUrl(value.modelGateway(), id, wireProtocol),
                            wireProtocol, model.get("maxTokens") instanceof Number n ? n.intValue() : null, true),
                            () -> Mono.just(value.headers()), http, descriptor));
                    });
            }));
    }
    public Mono<ModelClient> model(String connectionName) { return model(connectionName, null); }
    public ModelClient directModel(String model, URI baseUrl, ModelClient.Protocol protocol,
            Supplier<Mono<Map<String, String>>> headers) {
        checkOpen();
        return new ModelClient(new ModelClient.Settings(model, AgentConfig.httpUrl(baseUrl.toString()), protocol, null, false), headers, http);
    }
    public Mono<MCPClient> mcp(String name) {
        return mcp(name, null, Map.of());
    }
    public Mono<MCPClient> mcp(String name, String credentialName, Map<String, String> extraHeaders) {
        return controlPlane().flatMap(cp -> cp.list("ListMcps", "/mcp-servers", Map.of("name", name, "searchType", "accurate")))
            .map(items -> ControlPlane.exact(items, "name", name)).flatMap(item -> config.map(value -> {
                String root = value.mcpGateway().toString().replaceAll("/+$", "").replaceAll("/(mcp-servers|mcp)$", "");
                URI url = URI.create(root + "/mcp-servers/" + ControlPlane.encode(Json.text(item.get("mcpServerId"), "mcpServerId")));
                var custom = Map.copyOf(extraHeaders);
                Supplier<Mono<Map<String, String>>> headers = credentialName == null ? () -> Mono.just(Headers.merge(value.headers(), custom))
                    : () -> credentials().forMcp(credentialName, Json.text(item.get("mcpServerId"), "mcpServerId"))
                        .map(credential -> Headers.merge(value.headers(), credential.asHeaders(), custom));
                return track(new MCPClient(url, MCPClient.Transport.STREAMABLE_HTTP, headers));
            }));
    }
    public MCPClient directMcp(URI url, MCPClient.Transport transport, Supplier<Mono<Map<String, String>>> headers) {
        return track(new MCPClient(url, transport, headers));
    }
    public MCPClient stdioMcp(String command, List<String> args, Map<String, String> environment) {
        return track(MCPClient.stdio(command, args, environment));
    }
    public MemoryStore memory(String name) { checkOpen(); return new MemoryStore(name, this::controlPlane); }
    public io.agentcore.skill.Skills skills() { checkOpen(); return skills; }
    public io.agentcore.auth.BoundCredentials credentials() { checkOpen(); return new io.agentcore.auth.BoundCredentials(this::controlPlane, () -> controller); }
    public io.agentcore.runtime.BootstrapRuntime bootstrapRuntime() { checkOpen(); return bootstrap; }
    private synchronized MCPClient track(MCPClient client) { checkOpen(); mcps.add(client); return client; }
    private synchronized void checkOpen() { if (closed) throw new IllegalStateException("AgentCore is closed"); }
    public Mono<Void> closeAsync() {
        return Mono.defer(() -> {
            List<MCPClient> clients;
            synchronized (this) { closed = true; clients = List.copyOf(mcps); mcps.clear(); }
            if (bootstrap != null) bootstrap.close();
            return Mono.whenDelayError(Flux.fromIterable(clients).flatMapDelayError(MCPClient::closeAsync, 8, 1), Mono.fromRunnable(() -> {
                try { skills.close(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).subscribeOn(Schedulers.boundedElastic()));
        });
    }
    @Override public void close() { closeAsync().block(); }
    public static final class Builder {
        private Path configPath;
        private Path envPath;
        private long waitSeconds = Long.parseLong(System.getenv().getOrDefault("AGENTCORE_CONFIG_WAIT_TIMEOUT", "10"));
        private String workspaceId;
        private String regionId;
        private Path skillWorkspaceDir;
        private AccessKeyCredential credential;
        private URI controlEndpoint;
        private String bootstrapToken = System.getenv("AGENTCORE_DEBUG_TOKEN");
        private Builder() {}
        public Builder configPath(Path value) { configPath = value; return this; }
        public Builder envPath(Path value) { envPath = value; return this; }
        public Builder workspaceId(String value) { workspaceId = Json.text(value, "workspaceId"); return this; }
        public Builder regionId(String value) { regionId = Json.text(value, "regionId"); return this; }
        public Builder skillWorkspaceDir(Path value) { skillWorkspaceDir = value; return this; }
        public Builder accessKeyCredential(AccessKeyCredential value) { credential = value; return this; }
        public Builder bootstrapToken(String value) { bootstrapToken = value; return this; }
        public Builder controlPlaneEndpoint(URI value) { controlEndpoint = AgentConfig.httpUrl(value.toString()); return this; }
        public AgentCore build() {
            if (waitSeconds < 0) throw new IllegalArgumentException("AGENTCORE_CONFIG_WAIT_TIMEOUT cannot be negative");
            if ((workspaceId == null) != (regionId == null)) throw new IllegalArgumentException("workspaceId and regionId must be specified together");
            if (workspaceId != null && (configPath != null || envPath != null))
                throw new IllegalArgumentException("workspaceId and regionId cannot be combined with runtime file paths");
            if (workspaceId != null && bootstrapToken != null && !bootstrapToken.isBlank())
                throw new IllegalArgumentException("workspaceId and regionId cannot be combined with a bootstrap token");
            return new AgentCore(this);
        }
    }
}
