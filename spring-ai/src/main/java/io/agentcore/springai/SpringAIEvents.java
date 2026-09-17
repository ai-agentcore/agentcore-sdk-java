package io.agentcore.springai;

import io.agentcore.event.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.publisher.Flux;

/** Converts completed framework messages. Feed tool responses as well as assistant messages. */
public final class SpringAIEvents {
    private SpringAIEvents() {}
    /** Spring AI's ToolCallingAdvisor owns the loop; this adapter observes model and tool boundaries. */
    public static Flux<AgentEvent> stream(org.springframework.ai.chat.model.ChatModel model, org.springframework.ai.chat.prompt.Prompt prompt) {
        return Flux.create(sink -> {
            var observedModel = new org.springframework.ai.chat.model.ChatModel() {
                @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return model.getOptions(); }
                @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt input) { return model.call(input); }
                @Override public Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt input) {
                    return Flux.defer(() -> {
                        String id = UUID.randomUUID().toString(); sink.next(AgentEvent.textStart(id));
                        return new org.springframework.ai.chat.model.MessageAggregator().aggregate(model.stream(input), complete -> {
                            sink.next(AgentEvent.textEnd(id));
                            if (complete.getResult() != null) for (var call : complete.getResult().getOutput().getToolCalls()) {
                                sink.next(AgentEvent.toolStart(id, call.id(), call.name()));
                                sink.next(AgentEvent.toolArgs(call.id(), call.arguments())); sink.next(AgentEvent.toolEnd(call.id()));
                            }
                        }).doOnNext(chunk -> {
                            if (chunk.getResult() != null) {
                                String text = chunk.getResult().getOutput().getText();
                                if (text != null && !text.isEmpty()) sink.next(AgentEvent.text(id, text));
                            }
                        });
                    });
                }
            };
            var manager = org.springframework.ai.model.tool.ToolCallingManager.builder().build();
            var observedTools = new org.springframework.ai.model.tool.ToolCallingManager() {
                @Override public List<org.springframework.ai.tool.definition.ToolDefinition> resolveToolDefinitions(org.springframework.ai.model.tool.ToolCallingChatOptions options) {
                    return manager.resolveToolDefinitions(options);
                }
                @Override public org.springframework.ai.model.tool.ToolExecutionResult executeToolCalls(org.springframework.ai.chat.prompt.Prompt input, org.springframework.ai.chat.model.ChatResponse response) {
                    var result = manager.executeToolCalls(input, response);
                    var history = result.conversationHistory();
                    if (!history.isEmpty()) message(history.get(history.size() - 1)).forEach(sink::next);
                    return result;
                }
            };
            var client = org.springframework.ai.chat.client.ChatClient.builder(observedModel)
                .defaultAdvisors(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder().toolCallingManager(observedTools).build()).build();
            sink.onDispose(client.prompt(prompt).stream().chatClientResponse().subscribe(ignored -> {}, sink::error, sink::complete));
        });
    }
    public static Flux<AgentEvent> from(Flux<? extends Message> messages) {
        return messages.concatMapIterable(SpringAIEvents::message);
    }
    public static List<AgentEvent> message(Message message) {
        var events = new ArrayList<AgentEvent>();
        if (message instanceof AssistantMessage assistant) {
            String id = UUID.randomUUID().toString();
            events.add(AgentEvent.textStart(id));
            if (assistant.getText() != null && !assistant.getText().isEmpty()) events.add(AgentEvent.text(id, assistant.getText()));
            events.add(AgentEvent.textEnd(id));
            for (var call : assistant.getToolCalls()) {
                events.add(AgentEvent.toolStart(id, call.id(), call.name()));
                events.add(AgentEvent.toolArgs(call.id(), call.arguments())); events.add(AgentEvent.toolEnd(call.id()));
            }
        } else if (message instanceof ToolResponseMessage result) {
            for (var tool : result.getResponses()) events.add(AgentEvent.toolResult(tool.id(), tool.responseData()));
        }
        return events;
    }
}
