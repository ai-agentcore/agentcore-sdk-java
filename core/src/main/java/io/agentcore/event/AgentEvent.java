package io.agentcore.event;

import java.util.Map;

/** Execution events are separate from protocol envelopes and from model wire chunks. */
public record AgentEvent(Type type, Map<String, Object> data) {
    public enum Type { TEXT_START, TEXT_DELTA, TEXT_END, TOOL_START, TOOL_ARGS, TOOL_END, TOOL_RESULT }
    public AgentEvent { data = Map.copyOf(data); }
    public static AgentEvent textStart(String id) { return new AgentEvent(Type.TEXT_START, Map.of("messageId", id)); }
    public static AgentEvent text(String id, String delta) { return new AgentEvent(Type.TEXT_DELTA, Map.of("messageId", id, "delta", delta)); }
    public static AgentEvent textEnd(String id) { return new AgentEvent(Type.TEXT_END, Map.of("messageId", id)); }
    public static AgentEvent toolStart(String messageId, String id, String name) {
        return new AgentEvent(Type.TOOL_START, Map.of("parentMessageId", messageId, "toolCallId", id, "toolCallName", name));
    }
    public static AgentEvent toolArgs(String id, String delta) { return new AgentEvent(Type.TOOL_ARGS, Map.of("toolCallId", id, "delta", delta)); }
    public static AgentEvent toolEnd(String id) { return new AgentEvent(Type.TOOL_END, Map.of("toolCallId", id)); }
    public static AgentEvent toolResult(String id, String result) {
        return new AgentEvent(Type.TOOL_RESULT, Map.of("messageId", java.util.UUID.randomUUID().toString(), "toolCallId", id, "content", result));
    }
}
