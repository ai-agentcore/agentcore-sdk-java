package example;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.langchain4j.LangChain4jEvents;
import io.agentcore.server.AgentCoreServer;
import io.agentcore.tool.Tool;
import java.util.ArrayList;

public final class LangChainAgent {
    public interface Assistant { TokenStream chat(String input); }
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = AgentCoreLangChain4j.streamingModel(core.model("my-connection", "my-model").block());
            var tools = new ArrayList<Tool>(core.mcp("my-mcp").block().listTools().block());
            tools.addAll(core.skills().load("my-skill").block().tools());
            var assistant = AiServices.builder(Assistant.class).streamingChatModel(model).tools(AgentCoreLangChain4j.tools(tools)).build();
            try (var server = new AgentCoreServer(request -> LangChain4jEvents.from(() -> assistant.chat(
                Json.text(request.messages().get(request.messages().size() - 1).get("content"), "user content")))).start(8080)) {
                server.await();
            }
        }
    }
}
