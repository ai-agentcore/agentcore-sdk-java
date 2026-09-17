package example;

import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.springai.SpringAIEvents;
import io.agentcore.server.AgentCoreServer;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

public final class SpringAIAgent {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = AgentCoreSpringAI.model(core.model("my-connection", "my-model").block());
            var tools = core.mcp("my-mcp").block().listTools().block().stream().map(AgentCoreSpringAI::tool).toArray(ToolCallback[]::new);
            var options = ((ToolCallingChatOptions) model.getOptions()).mutate().toolCallbacks(tools).build();
            try (var server = new AgentCoreServer(request -> SpringAIEvents.stream(model, new Prompt(
                    Json.text(request.messages().get(request.messages().size() - 1).get("content"), "user content"), options))).start(8080)) {
                server.await();
            }
        }
    }
}
