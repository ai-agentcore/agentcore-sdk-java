package example;

import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.memory.MemoryScope;

public final class MemoryExample {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var store = core.memory("my-memory-store");
            var scope = new MemoryScope("example-user", "my-agent", "session-1");
            var submission = store.addMemories("用户喜欢简短的中文回答。", scope).block();
            var taskId = (String) Json.object(submission.get("data")).get("taskId");
            System.out.println("Memory task: " + taskId);
            // The task may still be pending.
            System.out.println(store.getMemoryTask(taskId).block());
            // For synchronous extraction: store.addMemories(text, scope, null, true).
            var readScope = new MemoryScope("example-user", "my-agent", null);
            System.out.println(store.searchMemories("用户有哪些回答偏好？", readScope, 5).block());
        }
    }
}
