package example;

import io.agentcore.Json;
import io.agentcore.event.AgentEvent;
import io.agentcore.server.AgentCoreServer;
import io.agentcore.server.AgentRequest;
import io.agentcore.server.InvokeHandler;
import io.agentcore.server.ProtocolHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

/** Protocol extension example; replace the echo handler with the application's Agent. */
public final class ServerExtensions {
    public static void main(String[] args) {
        var ready = new AtomicBoolean(false);
        InvokeHandler agent = request -> {
            String id = UUID.randomUUID().toString();
            return Flux.just(AgentEvent.textStart(id), AgentEvent.text(id, "Hello"), AgentEvent.textEnd(id));
        };
        var protocols = new ArrayList<>(AgentCoreServer.defaultProtocols());
        ProtocolHandler custom = (routes, invoke) -> routes.get("/custom", (request, response) -> {
            var input = new AgentRequest("custom", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), List.of(), Map.of(), Map.of());
            return response.header("Content-Type", "application/json")
                .sendString(invoke.invoke(input).collectList().map(Json::write));
        });
        protocols.add(custom);
        try (var server = new AgentCoreServer(agent, protocols, ready::get).start(8080)) {
            // Mark ready after application resources have been initialized.
            ready.set(true);
            server.await();
        }
    }
}
