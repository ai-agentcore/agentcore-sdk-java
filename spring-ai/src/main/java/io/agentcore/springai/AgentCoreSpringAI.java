package io.agentcore.springai;

import io.agentcore.Json;
import io.agentcore.model.ModelClient;
import io.agentcore.tool.Tool;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Uses Spring AI's native provider implementations and tool schemas. */
public final class AgentCoreSpringAI {
    private AgentCoreSpringAI() {}
    public static ProviderModelClient directModel(ChatModel model) { return new ProviderModelClient(model); }
    public static ProviderModelClient directModel(ChatModel model, org.springframework.ai.embedding.EmbeddingModel embedding) {
        return new ProviderModelClient(model, embedding);
    }
    public static ChatModel model(ModelClient source) {
        var settings = source.settings();
        okhttp3.Interceptor auth = chain -> {
            var request = chain.request().newBuilder().removeHeader("Authorization").removeHeader("x-api-key");
            source.headers().block().forEach(request::header);
            return chain.proceed(request.build());
        };
        if (settings.protocol() == ModelClient.Protocol.OPENAI)
            return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().model(settings.model()).baseUrl(settings.baseUrl().toString())
                .apiKey("agentcore").timeout(Duration.ofMinutes(10)).maxRetries(0).build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(auth)).build();
        return AnthropicChatModel.builder().options(AnthropicChatOptions.builder().model(settings.model()).baseUrl(settings.baseUrl().toString())
                .apiKey("agentcore").maxTokens(settings.maxTokens() == null ? 4096 : settings.maxTokens())
                .timeout(Duration.ofMinutes(10)).maxRetries(0).build())
            .httpClientBuilderCustomizer(builder -> builder.interceptor(auth)).build();
    }
    public static ToolCallback tool(Tool source) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name(source.name()).description(source.description())
                .inputSchema(Json.write(source.parameters())).build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) {
                return call(input, null);
            }
            @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                Object value = source.call(Json.read(input), context == null ? java.util.Map.of() : context.getContext()).block();
                return value instanceof String text ? text : Json.write(value);
            }
        };
    }
}
