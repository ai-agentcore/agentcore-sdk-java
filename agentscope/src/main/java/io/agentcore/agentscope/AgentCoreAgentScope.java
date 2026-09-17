package io.agentcore.agentscope;

import io.agentcore.Json;
import io.agentcore.model.ModelClient;
import io.agentcore.tool.Tool;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class AgentCoreAgentScope {
    private AgentCoreAgentScope() {}
    public static Model model(ModelClient source) {
        var settings = source.settings();
        Integer maxTokens = settings.maxTokens();
        if (maxTokens == null && settings.protocol() == ModelClient.Protocol.ANTHROPIC) maxTokens = 4096;
        var defaults = GenerateOptions.builder().maxTokens(maxTokens).build();
        Model delegate = settings.protocol() == ModelClient.Protocol.OPENAI
            ? OpenAIChatModel.builder().modelName(settings.model()).baseUrl(settings.baseUrl().toString())
                .endpointPath("/chat/completions").apiKey("agentcore").generateOptions(defaults).build()
            : AnthropicChatModel.builder().modelName(settings.model()).baseUrl(settings.baseUrl().toString()).defaultOptions(defaults).build();
        return new Model() {
            @Override public String getModelName() { return source.settings().model(); }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return source.headers().flatMapMany(headers -> {
                    var merged = GenerateOptions.mergeOptions(options, defaults);
                    var custom = merged.getAdditionalHeaders() == null ? Map.<String, String>of() : merged.getAdditionalHeaders();
                    var wireHeaders = new java.util.LinkedHashMap<>(io.agentcore.Headers.merge(headers, custom));
                    if (wireHeaders.containsKey("authorization")) wireHeaders.put("Authorization", wireHeaders.remove("authorization"));
                    var credentials = GenerateOptions.builder().additionalHeaders(wireHeaders).build();
                    return delegate.stream(messages, tools, GenerateOptions.mergeOptions(credentials, merged));
                });
            }
        };
    }
    public static AgentTool tool(Tool source) {
        return new AgentTool() {
            @Override public String getName() { return source.name(); }
            @Override public String getDescription() { return source.description(); }
            @Override public Map<String, Object> getParameters() { return source.parameters(); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                var context = param.getRuntimeContext();
                return source.call(param.getInput(), context == null ? Map.of() : context.getExtra()).map(value -> ToolResultBlock.of(param.getToolUseBlock().getId(), source.name(),
                    TextBlock.builder().text(value instanceof String text ? text : Json.write(value)).build()));
            }
        };
    }
}
