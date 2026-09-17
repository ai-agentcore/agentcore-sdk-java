# AgentCore SDK for Java

使用 Java 构建 Agent，连接 AgentCore 云端资源或自有服务，并与常用 Agent 框架集成。

[AgentCore 官网](https://www.aliyun.com/product/agentcore) · [官方文档](https://help.aliyun.com/zh/agentcore/) · [示例指南](examples/README.md)

## 从这里开始

| 你想做什么 | 入口 |
| --- | --- |
| 连接平台上的模型、MCP 和 Skill | [云端资源](#使用云端资源) |
| 使用自己的模型、MCP 或本地 Skill | [自定义资源](#使用自定义资源) |
| 为 Agent 添加长期记忆 | [Memory](#使用-memory) |
| 配置 MCP 凭证与固定 Header | [凭证与 Header](#凭证与-mcp-header) |
| 接入已有 Agent 框架 | [框架集成](#框架集成) |
| 将 Agent 发布为 HTTP 服务 | [服务协议](#提供-agent-服务) |

## 功能

- **模型**：访问托管模型，或连接自定义模型服务。
- **工具与 Skill**：接入 MCP 服务，使用云端或随应用分发的 Skill。
- **记忆**：检索和保存长期记忆，接入框架的 Agent 执行流程。
- **凭证**：按名称获取托管 API Key，或为 MCP 显式绑定 Header 凭证。
- **服务协议**：通过 AG-UI 或 OpenAI Chat Completions 提供 Agent 服务。

支持 Spring AI、LangChain4j 和 AgentScope Java。

## 安装

需要 JDK 17+、Maven 3.9+。在仓库根目录构建并安装到本地 Maven 仓库：

```bash
mvn install
```

在应用中添加所需模块，例如基础 SDK：

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

框架和服务端模块按需添加，不是基础 SDK 的默认依赖：

| artifactId | 用途 |
| --- | --- |
| `agentcore-sdk` | Model、MCP、Skill、Memory、Credential |
| `agentcore-sdk-server` | AG-UI、OpenAI Chat Completions 服务 |
| `agentcore-sdk-spring-ai` | Spring AI |
| `agentcore-sdk-langchain4j` | LangChain4j |
| `agentcore-sdk-agentscope` | AgentScope Java |

以上模块使用相同的 `groupId` 和版本。框架依赖与完整示例见[示例指南](examples/README.md)。

## 快速开始

### 使用云端资源

先参考[官方文档](https://help.aliyun.com/zh/agentcore/)创建 Workspace，再按需准备模型连接、MCP 和 Skill，并为 Agent 授予访问权限。以下代码在部署到 AgentCore 的应用中运行；将资源名称替换为自己的资源名称。只使用模型时，无需创建 MCP 或 Skill。

```java
import io.agentcore.AgentCore;
import java.util.List;
import java.util.Map;

public class QuickStart {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = core.model("my-connection", "my-model").block();
            var response = model.completion(
                List.of(Map.of("role", "user", "content", "你好"))
            ).block();
            System.out.println(response);

            var mcp = core.mcp("my-mcp").block();
            System.out.println(mcp.listTools().block().stream()
                .map(tool -> tool.name()).toList());

            var skill = core.skills().load("my-skill", "1.0.0").block();
            System.out.println(skill.name());
        }
    }
}
```

基础 API 返回 Reactor `Mono` / `Flux`。示例在普通 Java 主线程中使用 `block()`；响应式应用应组合或订阅返回值，不要阻塞事件循环线程。

完整示例见 [CloudResources.java](examples/src/main/java/example/CloudResources.java)。

### 凭证与 MCP Header

在平台创建凭证并授权后，使用 `core.credentials().get(name)` 按名称获取。API Key 凭证通过 `credential.value()` 读取；MCP Header 凭证通过 `credential.asHeaders()` 读取。不要将凭证值写入日志或提交到代码仓库。

托管 MCP 可在创建时显式绑定 MCP Header 凭证，并追加固定 Header。只有该 MCP 在凭证允许的应用范围内时才能使用：

```java
var mcp = core.mcp(
    "my-mcp",
    "my-header-credential",
    Map.of("X-App", "example")
).block();
```

Header 作用于该客户端的握手、工具发现和工具调用，不是请求级身份上下文。Header 名称大小写不敏感；冲突会报错，不静默覆盖。`Authorization`、平台已配置的 Header 及 MCP 协议保留字段不能被覆盖。

不要修改共享客户端来切换用户身份。SDK 不自动透传入站请求头，固定 Header 也不等于可信身份。直连 MCP 可以通过 Header Provider 提供凭证；stdio 不承载 HTTP Header。

### 使用自定义资源

连接自有模型和 MCP 服务，无需在 AgentCore 注册资源：

```java
import io.agentcore.AgentCore;
import io.agentcore.model.ModelClient;
import io.agentcore.mcp.MCPClient;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class DirectResources {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var model = core.directModel(
                System.getenv("CUSTOM_MODEL_NAME"),
                URI.create(System.getenv("CUSTOM_MODEL_BASE_URL")),
                ModelClient.Protocol.OPENAI,
                () -> Mono.just(Map.of(
                    "Authorization", "Bearer " + System.getenv("CUSTOM_MODEL_API_KEY")
                ))
            );
            System.out.println(model.completion(
                List.of(Map.of("role", "user", "content", "你好"))
            ).block());

            var mcp = core.directMcp(
                URI.create(System.getenv("CUSTOM_MCP_URL")),
                MCPClient.Transport.STREAMABLE_HTTP,
                () -> Mono.just(Map.of())
            );
            System.out.println(mcp.listTools().block().stream()
                .map(tool -> tool.name()).toList());
        }
    }
}
```

直连模型支持 OpenAI 和 Anthropic 协议。OpenAI `baseUrl` 通常以 `/v1` 结尾，Anthropic `baseUrl` 不包含末尾 `/v1`。直连 MCP 还支持 SSE 和 stdio。

本地 Skill 使用 `core.skills().local(path)` 加载。完整配置见[自定义资源示例](examples/src/main/java/example/DirectResources.java)和 [Skill 使用说明](examples/README.md#使用-skill)。

## 使用 Memory

Memory 用于保存和检索长期记忆，也支持查询、更新、删除记忆以及查看会话消息。使用前，在 AgentCore 中创建 MemoryStore 并授予应用访问权限。

```java
import io.agentcore.AgentCore;
import io.agentcore.memory.MemoryScope;

public class MemoryExample {
    public static void main(String[] args) {
        try (var core = AgentCore.auto()) {
            var store = core.memory("my-memory-store");
            var writeScope = new MemoryScope("example-user", "my-agent", "session-1");
            store.addMemories("用户喜欢简短的中文回答。", writeScope).block();

            // 跨会话检索该用户的记忆；新写入的记忆可能需要一定时间才能检索到。
            var readScope = new MemoryScope("example-user", "my-agent", null);
            var result = store.searchMemories("用户有哪些回答偏好？", readScope, 5).block();
            System.out.println(result);
        }
    }
}
```

`userId`、`agentId`、`sessionId` 是独立的记忆范围字段，由可信业务上下文提供，不替代访问权限控制。查询时只指定需要匹配的字段；查看会话消息时需要 `sessionId`，并至少提供 `userId` 或 `agentId`。

示例会写入真实数据，建议使用测试记忆空间。新写入的记忆可能需要一定时间才能检索到，首次查询可能为空。

需要 Agent 自动检索和使用记忆时，参见 [Memory 框架示例](examples/src/main/java/example/MemoryAdapters.java)。自动回写默认关闭，显式开启后只保存本轮用户输入和最终文本答案，不重复写入历史消息或工具结果。每个请求应使用对应用户的 `MemoryContext`，不要混用不同用户的记忆范围。

## 框架集成

将模型、MCP、Skill 和 Memory 接入现有框架，不必重写 Agent 的业务逻辑。

| 框架 | 适配模块 | 示例 |
| --- | --- | --- |
| Spring AI | `agentcore-sdk-spring-ai` | [Agent 与工具循环](examples/src/main/java/example/SpringAIAgent.java) |
| LangChain4j | `agentcore-sdk-langchain4j` | [Agent 与 HTTP 服务](examples/src/main/java/example/LangChainAgent.java) |
| AgentScope Java | `agentcore-sdk-agentscope` | [Agent 与执行事件](examples/src/main/java/example/AgentScopeAgent.java) |

三个框架均提供 Memory 适配，见 [MemoryAdapters.java](examples/src/main/java/example/MemoryAdapters.java)。

使用自有模型时，可以直接使用所选框架的原生 Provider，再接入 AgentCore 工具和 Memory。见[原生模型示例](examples/src/main/java/example/NativeFrameworkAgents.java)。不使用 Agent 编排框架时，也可通过 Spring AI 模块调用其他供应商模型，见 [ProviderModels.java](examples/src/main/java/example/ProviderModels.java)。

## 提供 Agent 服务

`AgentCoreServer` 支持 AG-UI、OpenAI Chat Completions 和自定义 `ProtocolHandler`。在应用中添加 `agentcore-sdk-server` 及所用框架模块。

[LangChain4j 服务示例](examples/src/main/java/example/LangChainAgent.java)将托管模型、MCP、Skill 和框架执行事件接入 HTTP 服务。启动 `example.LangChainAgent` 后监听 8080 端口：

| 接口 | 用途 |
| --- | --- |
| `POST /agui` | AG-UI 流式响应 |
| `POST /openai/v1/chat/completions` | OpenAI Chat Completions，支持普通和流式响应 |
| `GET /healthz` | 进程存活检查 |
| `GET /readyz` | 就绪检查 |

服务示例每次处理最新一条用户文本，不保存会话历史。入口鉴权和会话历史由应用或所用框架负责。调用方式见[调用 Agent 服务](examples/README.md#调用-agent-服务)，自定义协议与就绪检查见 [ServerExtensions.java](examples/src/main/java/example/ServerExtensions.java)。

展示工具执行过程时，使用对应框架的事件转换器，不要只提取文本：

- `SpringAIEvents.stream(model, prompt)`
- `LangChain4jEvents.from(() -> assistant.chat(input))`
- `AgentScopeEvents.from(agent.streamEvents(input))`

AG-UI 可表达消息边界、工具调用和工具结果。OpenAI Chat Completions 按其标准表达文本和工具调用，不能完整表达 Agent 内部的多轮消息边界或中间工具结果。

## 使用提示

- 在应用生命周期内复用 `AgentCore` 及资源客户端，退出时调用 `close()` 或使用 try-with-resources。
- 模型聊天调用使用 `completion()`，流式调用使用 `stream()`。OpenAI 协议的 Responses 调用使用 `responses()` / `responsesStream()`，支持情况取决于所选模型。
- 仅加载可信 Skill；命令以应用进程权限执行。不需要执行命令时设置 `ALLOW_EXECUTE_COMMAND=false`。
- SDK 使用 SLF4J，应用自行配置 Logback 等日志实现。排查失败时查看操作名、状态码、错误码及上游 RequestId；服务端未返回的字段可能为空。
- `AgentCoreException` 提供 `operation()`、`status()`、`serviceCode()`、`requestId()` 等诊断信息。不要在日志中记录 API Key、原始认证 Header 或敏感对话内容。

更多资源配置、框架依赖和示例见[示例指南](examples/README.md)。

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
