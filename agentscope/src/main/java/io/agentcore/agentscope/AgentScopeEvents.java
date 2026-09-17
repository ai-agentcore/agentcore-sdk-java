package io.agentcore.agentscope;

import io.agentcore.Json;
import io.agentcore.event.AgentEvent;
import io.agentscope.core.event.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

public final class AgentScopeEvents {
    private AgentScopeEvents() {}
    public static Flux<AgentEvent> from(Flux<? extends io.agentscope.core.event.AgentEvent> source) {
        return Flux.defer(() -> {
            Map<String, String> messageIds = new HashMap<>();
            Map<String, String> blocks = new HashMap<>();
            Map<String, List<Object>> results = new HashMap<>();
            return source.handle((event, sink) -> {
                if (event instanceof TextBlockStartEvent text) {
                    String id = java.util.UUID.randomUUID().toString();
                    blocks.put(text.getReplyId() + "\0" + text.getBlockId(), id);
                    messageIds.put(text.getReplyId(), id); sink.next(AgentEvent.textStart(id));
                } else if (event instanceof TextBlockDeltaEvent text) sink.next(AgentEvent.text(blocks.get(text.getReplyId() + "\0" + text.getBlockId()), text.getDelta()));
                else if (event instanceof TextBlockEndEvent text) sink.next(AgentEvent.textEnd(blocks.remove(text.getReplyId() + "\0" + text.getBlockId())));
                else if (event instanceof ToolCallStartEvent call) sink.next(AgentEvent.toolStart(messageIds.getOrDefault(call.getReplyId(), call.getReplyId()), call.getToolCallId(), call.getToolCallName()));
                else if (event instanceof ToolCallDeltaEvent call) sink.next(AgentEvent.toolArgs(call.getToolCallId(), call.getDelta()));
                else if (event instanceof ToolCallEndEvent call) sink.next(AgentEvent.toolEnd(call.getToolCallId()));
                else if (event instanceof ToolResultTextDeltaEvent result) results.computeIfAbsent(result.getToolCallId(), id -> new ArrayList<>()).add(Map.of("type", "text", "text", result.getDelta()));
                else if (event instanceof ToolResultDataDeltaEvent result) results.computeIfAbsent(result.getToolCallId(), id -> new ArrayList<>()).add(result.getData());
                else if (event instanceof ToolResultEndEvent result) {
                    var content = results.remove(result.getToolCallId());
                    sink.next(AgentEvent.toolResult(result.getToolCallId(), Json.write(Map.of("content", content == null ? List.of() : content, "state", String.valueOf(result.getState())))));
                }
            });
        });
    }
}
