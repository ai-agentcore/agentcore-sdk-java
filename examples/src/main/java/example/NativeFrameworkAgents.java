package example;

import io.agentcore.agentscope.AgentCoreAgentScope;
import io.agentcore.agentscope.AgentScopeEvents;
import io.agentcore.event.AgentEvent;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.langchain4j.LangChain4jEvents;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.springai.SpringAIEvents;
import io.agentcore.tool.Tool;
import java.util.List;
import reactor.core.publisher.Flux;

/** The application configures and owns its framework-native provider model. */
public final class NativeFrameworkAgents {
    private NativeFrameworkAgents() {}

    // Tools may come from AgentCore MCP, Skill, collaboration, or application code.
    public static Flux<AgentEvent> spring(org.springframework.ai.chat.model.ChatModel model,
            List<Tool> tools, String input) {
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolCallbacks(tools.stream().map(AgentCoreSpringAI::tool).toList()).build();
        return SpringAIEvents.stream(model, new org.springframework.ai.chat.prompt.Prompt(input, options));
    }

    public interface Assistant { dev.langchain4j.service.TokenStream chat(String input); }

    public static Flux<AgentEvent> langchain4j(dev.langchain4j.model.chat.StreamingChatModel model,
            List<Tool> tools, String input) {
        var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
            .streamingChatModel(model).tools(AgentCoreLangChain4j.tools(tools)).build();
        return LangChain4jEvents.from(() -> assistant.chat(input));
    }

    public static Flux<AgentEvent> agentscope(io.agentscope.core.model.Model model,
            List<Tool> tools, String input) {
        return Flux.using(() -> {
            var toolkit = new io.agentscope.core.tool.Toolkit();
            tools.forEach(tool -> toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool)));
            return io.agentscope.core.ReActAgent.builder().name("assistant").model(model).toolkit(toolkit).build();
        }, agent -> AgentScopeEvents.from(agent.streamEvents(input)), io.agentscope.core.ReActAgent::close);
    }
}
