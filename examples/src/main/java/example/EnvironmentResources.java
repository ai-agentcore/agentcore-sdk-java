package example;

import io.agentcore.AgentCore;

/** Set resource-selection environment variables from README before starting. */
public final class EnvironmentResources {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = core.model().block();
            var mcps = core.mcpsFromEnv().block();
            var skills = core.skills().fromEnv().block();
            var template = core.templates().get().block();
            System.out.println("Model: " + model.settings().model());
            for (var mcp : mcps) System.out.println("MCP tools: " + mcp.listTools().block());
            System.out.println("Skills: " + skills.stream().map(io.agentcore.skill.Skill::name).toList());
            System.out.println("AgentSpec: " + template.name() + " " + template.version());
            // Load AgentSpec Skills explicitly with template.loadSkills() when needed.
            // Pass the selected resources to your framework adapters.
        }
    }
}
