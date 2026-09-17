package example;

import io.agentcore.event.AgentEvent;
import io.agentcore.springai.AgentCoreSpringAI;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import reactor.core.publisher.Flux;

/** Pass a provider configured by your application (Ollama, Gemini, Bedrock, etc.). */
public final class ProviderModels {
    public static Flux<AgentEvent> invoke(ChatModel model, String input) {
        return AgentCoreSpringAI.directModel(model).events(new Prompt(input));
    }
    public static void completionAndEmbedding(ChatModel model, EmbeddingModel embedding) {
        var client = AgentCoreSpringAI.directModel(model, embedding);
        var response = client.completion(new Prompt("Hello")).block();
        var vectors = client.embedding(new EmbeddingRequest(java.util.List.of("Hello"), null)).block();
        // Consume Spring AI's native ChatResponse/EmbeddingResponse in your application.
    }
}
