package example;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.invocation.InvocationParameters;
import io.agentcore.collaboration.Collaboration;
import io.agentcore.collaboration.CollaborationContext;
import io.agentcore.event.AgentEvent;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.langchain4j.LangChain4jEvents;
import java.util.Map;
import reactor.core.publisher.Flux;

/** Called by an application's own HTTP/message handler; AgentCoreServer is not required. */
public final class CollaborationAgent {
    public interface Assistant { TokenStream chat(@dev.langchain4j.service.UserMessage String message, InvocationParameters parameters); }
    private final Assistant assistant;

    public CollaborationAgent(StreamingChatModel model, Collaboration collaboration) {
        this(model, collaboration, java.nio.file.Path.of("."));
    }
    public CollaborationAgent(StreamingChatModel model, Collaboration collaboration, java.nio.file.Path workspace) {
        // Initialize once at application startup, not inside a request or a Reactor event loop.
        var tools = new java.util.ArrayList<>(collaboration.tools().block());
        tools.addAll(io.agentcore.skill.Skill.tools(collaboration.skills().block(), workspace));
        this.assistant = AiServices.builder(Assistant.class).streamingChatModel(model)
            .tools(AgentCoreLangChain4j.tools(tools))
            .systemMessageProvider(id -> collaboration.composePrompt("你是应用助手。"))
            .build();
    }

    public Flux<AgentEvent> invoke(Map<String, String> trustedPlatformHeaders, String input) {
        return Flux.defer(() -> {
            var context = CollaborationContext.fromHeaders(trustedPlatformHeaders);
            var parameters = InvocationParameters.from(context == null ? Map.of() : Map.of(CollaborationContext.KEY, context));
            return LangChain4jEvents.from(() -> assistant.chat(input, parameters));
        });
    }
}
