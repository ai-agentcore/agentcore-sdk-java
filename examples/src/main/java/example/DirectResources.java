package example;

import io.agentcore.AgentCore;
import io.agentcore.mcp.MCPClient;
import io.agentcore.model.ModelClient;
import java.net.URI;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public final class DirectResources {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = core.directModel("my-model", URI.create("https://models.example.com/v1"), ModelClient.Protocol.OPENAI,
                () -> Mono.just(Map.of("Authorization", "Bearer " + System.getenv("MODEL_API_KEY"))));
            model.stream(List.of(Map.of("role", "user", "content", "Hello"))).doOnNext(System.out::println).blockLast();
            var mcp = core.directMcp(URI.create("https://tools.example.com/mcp"), MCPClient.Transport.STREAMABLE_HTTP, () -> Mono.just(Map.of()));
            System.out.println(mcp.listTools().block().stream().map(tool -> tool.name()).toList());
        }
    }
}
