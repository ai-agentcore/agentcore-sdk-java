# 集成 AgentScope Java

使用 `ReActAgent` 创建 Agent，将 AgentCore 托管模型、MCP 或 Skill 工具接入 AgentScope Java。

## 添加依赖

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk-agentscope</artifactId>
  <version>0.1.0</version>
</dependency>
```

该模块包含基础 SDK 和 AgentScope Java 2.0.3 依赖，不需要 Spring AI。以下 HTTP 服务示例还需添加：

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk-server</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 接入模型和工具

在 Workspace 中准备模型连接、支持工具调用的模型和 MCP。以下片段放在 `try (var core = AgentCore.auto())` 中，在 AgentCore 托管环境运行；替换占位资源名称。

```java
var model = AgentCoreAgentScope.model(core.model("my-connection", "my-model").block());
var toolkit = new Toolkit();
core.mcp("my-mcp").block().listTools().block()
    .forEach(tool -> toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool)));
```

`AgentCoreAgentScope.model(...)` 提供 AgentScope 模型接口；`tool(...)` 将工具描述和执行函数转换为可注册的 AgentTool。要添加 Skill，将 `skill.tools()` 中的工具通过相同方式注册到 Toolkit。

## 输出执行事件并提供服务

```java
try (var server = new AgentCoreServer(request -> Flux.using(
        () -> ReActAgent.builder().name("assistant").model(model).toolkit(toolkit).maxIters(8).build(),
        agent -> AgentScopeEvents.from(agent.streamEvents(
            Json.text(request.messages().get(request.messages().size() - 1).get("content"), "user content"))),
        ReActAgent::close)).start(8080)) {
    server.await();
}
```

此示例复用模型和工具定义，每个请求创建独立的 `ReActAgent`，避免不同用户共享 Agent 的会话状态；结束、失败或取消后由 `Flux.using` 关闭 Agent。

`AgentScopeEvents.from(agent.streamEvents(...))` 转换框架执行事件，保留消息边界、工具调用和结果。不要仅调用 `getTextContent()` 来输出完整执行过程。

完整可编译代码及 imports 见 [AgentScopeAgent.java](../examples/src/main/java/example/AgentScopeAgent.java)。构建、启动命令见[示例指南](../examples/README.md#准备资源与运行示例)，AG-UI/OpenAI 请求示例见[调用 Agent 服务](../examples/README.md#调用-agent-服务)。该示例只处理本次请求最后一条用户文本，不提供跨请求历史存储。

## 添加 Memory 或使用原生模型

在创建 `ReActAgent` 时注册 `.middleware(new MemoryMiddleware(memory, true))`，其中 `memory` 是本次请求对应的 `MemoryContext`，`true` 表示显式开启本轮对话回写。完整上下文构造和调用见 [MemoryAdapters.agentscope](../examples/src/main/java/example/MemoryAdapters.java) 与 [Memory 指南](../examples/README.md#为框架添加-memory)。

需要流式服务时，仍通过 `agent.streamEvents(...)` 与 `AgentScopeEvents.from(...)` 输出事件，Memory Middleware 随该 Agent 的执行流程生效。长期记忆范围与 Agent 的短期会话状态分别管理。

已有原生 AgentScope 模型时，可以直接用它代替托管模型，再注册 AgentCore 工具，见 [NativeFrameworkAgents.agentscope](../examples/src/main/java/example/NativeFrameworkAgents.java)。供应商配置与认证由应用负责。

AG-UI 支持完整消息边界和工具结果；OpenAI Chat Completions 仅表达协议支持的文本和工具调用。
