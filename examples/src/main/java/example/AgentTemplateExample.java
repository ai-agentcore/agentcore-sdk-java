package example;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.agentcore.AgentCore;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.skill.Skill;
import java.nio.file.Path;

public final class AgentTemplateExample {
    public interface Assistant {
        @SystemMessage("{{instruction}}")
        String chat(@V("instruction") String instruction, @UserMessage String message);
    }
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var template = core.templates().get("my-agent-template", "1.0.0").block();
            var skills = template.loadSkills().block();
            var model = AgentCoreLangChain4j.model(core.model("my-connection", "my-model").block());
            var assistant = AiServices.builder(Assistant.class).chatModel(model)
                .tools(AgentCoreLangChain4j.tools(Skill.tools(skills, Path.of(".")))).build();
            System.out.println(assistant.chat(template.instruction(), "介绍你可以使用的技能。"));
        }
    }
}
