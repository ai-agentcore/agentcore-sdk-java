package example;

import io.agentcore.memory.MemoryContext;
import io.agentcore.model.ModelClient;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.agentscope.AgentCoreAgentScope;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Choose a MemoryContext for the authenticated user in the application's request handler. */
public final class MemoryAdapters {
    public interface Assistant { dev.langchain4j.service.Result<String> chat(String input); }
    public static Mono<String> spring(ModelClient model, MemoryContext memory, String input) {
        return Mono.fromCallable(() -> org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(model))
            .defaultAdvisors(new io.agentcore.springai.MemoryAdvisor(memory, true)).build()
            .prompt().user(input).call().content()).subscribeOn(Schedulers.boundedElastic());
    }
    public static Mono<String> langchain4j(ModelClient model, MemoryContext memory, String input) {
        var adapter = new io.agentcore.langchain4j.MemoryAdapter(memory, true);
        var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
            .chatModel(AgentCoreLangChain4j.model(model)).contentRetriever(adapter.retriever()).build();
        return adapter.call(input, () -> assistant.chat(input)).map(dev.langchain4j.service.Result::content);
    }
    public static Mono<String> agentscope(ModelClient model, MemoryContext memory, String input) {
        return Mono.using(() -> io.agentscope.core.ReActAgent.builder().name("assistant")
                .model(AgentCoreAgentScope.model(model)).middleware(new io.agentcore.agentscope.MemoryMiddleware(memory, true)).build(),
            agent -> agent.call(input).map(io.agentscope.core.message.Msg::getTextContent), io.agentscope.core.ReActAgent::close);
    }
}
