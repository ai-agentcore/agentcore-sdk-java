package example;

import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.agentscope.AgentCoreAgentScope;
import io.agentcore.agentscope.AgentScopeEvents;
import io.agentcore.server.AgentCoreServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;

public final class AgentScopeAgent {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = AgentCoreAgentScope.model(core.model("my-connection", "my-model").block());
            var toolkit = new Toolkit();
            core.mcp("my-mcp").block().listTools().block().forEach(tool -> toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool)));
            // An Agent per request avoids sharing conversation state across unrelated users.
            try (var server = new AgentCoreServer(request -> Flux.using(
                    () -> ReActAgent.builder().name("assistant").model(model).toolkit(toolkit).maxIters(8).build(),
                    agent -> AgentScopeEvents.from(agent.streamEvents(Json.text(request.messages().get(request.messages().size() - 1).get("content"), "user content"))),
                    ReActAgent::close)).start(8080)) {
                server.await();
            }
        }
    }
}
