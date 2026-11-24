package example;

import io.agentcore.AgentCore;
import io.agentcore.knowledgebase.KnowledgeBase;

public final class KnowledgeBaseExample {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var kb = core.knowledgeBase("你的知识库 ID");
            var result = kb.search("产品如何配置？", new KnowledgeBase.SearchOptions(5)).block();
            for (var chunk : result.chunks()) {
                System.out.println(chunk.fileName() + ": " + chunk.content());
            }
        }
    }
}
