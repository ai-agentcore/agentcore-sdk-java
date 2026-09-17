package example;

import io.agentcore.AgentCore;
import io.agentcore.memory.MemoryScope;
import java.util.List;
import java.util.Map;

public final class CloudResources {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = core.model("my-connection", "my-model").block();
            System.out.println(model.descriptor());
            System.out.println(model.completion(List.of(Map.of("role", "user", "content", "你好"))).block());
            var mcp = core.mcp("my-mcp").block();
            System.out.println(mcp.listTools().block().stream().map(tool -> tool.name()).toList());
            var skill = core.skills().load("my-skill", "1.0.0").block();
            System.out.println(skill.name() + ": " + skill.description());
            var scope = new MemoryScope("example-user", "example-agent", "example-session");
            var options = new io.agentcore.memory.MemoryStore.SearchOptions(5, Map.of("category", "preference"), true, null, null);
            System.out.println(core.memory("my-memory-store").searchMemories("用户偏好", scope, options).block());
        }
    }
}
