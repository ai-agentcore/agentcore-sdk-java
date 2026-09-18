package example;

import io.agentcore.AgentCore;
import io.agentcore.memory.MemoryContext;
import io.agentcore.memory.MemoryScope;
import io.agentcore.model.ModelClient;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.agentscope.AgentCoreAgentScope;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Choose a MemoryContext for the authenticated user in the application's request handler. */
public final class MemoryAdapters {
    public static void main(String[] args) {
        String framework = args.length == 0 ? "langchain4j" : args[0];
        try (var core = AgentCore.auto()) {
            var model = core.model("my-connection", "my-model").block();
            var store = core.memory("my-memory-store");
            // In an application, derive these values from the authenticated request.
            var readScope = new MemoryScope("example-user", "example-agent", null);
            var writeScope = new MemoryScope("example-user", "example-agent", "example-session");
            var memory = new MemoryContext(store, readScope, writeScope, 5);
            String input = "我喜欢简短的中文回答。请给我一条学习 Java 的建议。";
            // Each adapter below explicitly enables writing this completed turn.
            Mono<String> answer = switch (framework) {
                case "spring-ai" -> spring(model, memory, input);
                case "langchain4j" -> langchain4j(model, memory, input);
                case "agentscope" -> agentscope(model, memory, input);
                default -> throw new IllegalArgumentException("Choose spring-ai, langchain4j or agentscope");
            };
            System.out.println(answer.block());
        }
    }

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
