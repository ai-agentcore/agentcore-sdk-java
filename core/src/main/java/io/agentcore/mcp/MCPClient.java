package io.agentcore.mcp;

import io.agentcore.Headers;
import io.agentcore.tool.Tool;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** A persistent MCP session. Header suppliers are evaluated when a session connects. */
public final class MCPClient implements AutoCloseable {
    public enum Transport { STREAMABLE_HTTP, SSE }
    private final Supplier<Mono<McpClientTransport>> transport;
    private final Duration metadataTimeout;
    private final Duration toolTimeout;
    private Mono<McpAsyncClient> connection;
    private McpAsyncClient active;
    private boolean closed;
    private final reactor.core.publisher.Sinks.Empty<Void> closing = reactor.core.publisher.Sinks.empty();

    public MCPClient(URI url, Transport protocol, Supplier<Mono<Map<String, String>>> headers) {
        this(() -> Mono.defer(headers).map(values -> {
            var request = HttpRequest.newBuilder();
            Headers.merge(values).forEach(request::header);
            String base = url.getScheme() + "://" + url.getAuthority();
            String path = url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
            return protocol == Transport.STREAMABLE_HTTP
                ? HttpClientStreamableHttpTransport.builder(base).endpoint(path).requestBuilder(request)
                    .connectTimeout(Duration.ofSeconds(10)).build()
                : HttpClientSseClientTransport.builder(base).sseEndpoint(path).requestBuilder(request)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        }), Duration.ofSeconds(60), Duration.ofMinutes(10));
    }
    private MCPClient(Supplier<Mono<McpClientTransport>> transport, Duration metadataTimeout, Duration toolTimeout) {
        this.transport = transport; this.metadataTimeout = metadataTimeout; this.toolTimeout = toolTimeout;
    }
    public static MCPClient stdio(String command, List<String> args, Map<String, String> env) {
        return new MCPClient(() -> Mono.fromSupplier(() -> new StdioClientTransport(
            ServerParameters.builder(command).args(args).env(env).build(), McpJsonDefaults.getMapper())),
            Duration.ofSeconds(60), Duration.ofMinutes(10));
    }
    public Mono<List<Tool>> listTools() {
        return execute(client -> client.listTools().expand(page -> page.nextCursor() == null ? Mono.empty()
            : client.listTools(page.nextCursor())).flatMapIterable(McpSchema.ListToolsResult::tools)
            .map(tool -> new Tool(tool.name(), tool.description() == null ? "" : tool.description(),
                tool.inputSchema(), args -> callTool(tool.name(), args).cast(Object.class))).collectList(), metadataTimeout);
    }
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        return execute(client -> client.callTool(new McpSchema.CallToolRequest(name, arguments, null)), toolTimeout);
    }
    private <T> Mono<T> execute(Function<McpAsyncClient, Mono<T>> operation, Duration timeout) {
        return Mono.defer(this::connect).flatMap(client -> operation.apply(client).timeout(timeout)
            // A request deadline does not mean the shared Session has disconnected.
            .onErrorResume(error -> error instanceof McpError || error instanceof TimeoutException ? Mono.error(error)
                : invalidate(client).then(Mono.error(error))));
    }
    private synchronized Mono<McpAsyncClient> connect() {
        if (closed) return Mono.error(new IllegalStateException("MCP client is closed"));
        if (connection == null) connection = Mono.defer(transport).flatMap(value -> {
            var client = McpClient.async(value).requestTimeout(toolTimeout)
                .initializationTimeout(Duration.ofSeconds(30)).build();
            synchronized (this) {
                if (closed) { client.close(); return Mono.error(new IllegalStateException("MCP client is closed")); }
                active = client;
            }
            return Mono.usingWhen(Mono.just(client), valueClient -> valueClient.initialize().thenReturn(valueClient),
                valueClient -> Mono.empty(), (valueClient, error) -> invalidate(valueClient), this::invalidate);
        }).takeUntilOther(closing.asMono()).switchIfEmpty(Mono.error(new IllegalStateException("MCP client is closed")))
            .cacheInvalidateIf(value -> false);
        return connection;
    }
    private Mono<Void> invalidate(McpAsyncClient client) {
        synchronized (this) {
            if (active != client) return Mono.empty();
            connection = null; active = null;
        }
        return client.closeGracefully().onErrorComplete();
    }
    public Mono<Void> closeAsync() {
        return Mono.defer(() -> {
            McpAsyncClient client;
            synchronized (this) { closed = true; client = active; active = null; connection = null; }
            closing.tryEmitEmpty();
            return client == null ? Mono.empty() : client.closeGracefully();
        });
    }
    @Override public void close() { closeAsync().block(); }
}
