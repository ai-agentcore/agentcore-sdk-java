# 集成 Spring AI

将 AgentCore 的托管模型转换为 Spring AI 模型，将 MCP 或 Skill 工具转换为 `ToolCallback`，即可使用 Spring AI 的工具调用流程。

## 添加依赖

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk-spring-ai</artifactId>
  <version>0.1.0</version>
</dependency>
```

该模块包含基础 SDK 和 Spring AI 2.0.1 依赖，不需要额外添加基础 SDK。以下 HTTP 服务示例还需添加：

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
var model = AgentCoreSpringAI.model(core.model("my-connection", "my-model").block());
var tools = core.mcp("my-mcp").block().listTools().block().stream()
    .map(AgentCoreSpringAI::tool).toArray(ToolCallback[]::new);
var options = ((ToolCallingChatOptions) model.getOptions()).mutate()
    .toolCallbacks(tools).build();
```

`AgentCoreSpringAI.model(...)` 提供 Spring AI 模型接口；`AgentCoreSpringAI.tool(...)` 提供工具定义和执行函数。Skill 的 `tools()` 返回同一种 AgentCore Tool，可通过相同方法转换后加入工具集合。

保留模型自身的 options，再追加工具，避免丢失已配置的模型参数。此用法不要求使用 Spring Boot。

## 输出执行事件并提供服务

```java
try (var server = new AgentCoreServer(request -> SpringAIEvents.stream(model, new Prompt(
        Json.text(request.messages().get(request.messages().size() - 1).get("content"), "user content"),
        options))).start(8080)) {
    server.await();
}
```

`SpringAIEvents.stream(model, prompt)` 驱动流式工具循环，并将文本、工具调用和工具结果转换为通用执行事件。不要仅转发最终文本，否则会丢失工具执行过程。

完整可编译代码及 imports 见 [SpringAIAgent.java](../examples/src/main/java/example/SpringAIAgent.java)。构建、启动命令见[示例指南](../examples/README.md#准备资源与运行示例)，AG-UI/OpenAI 请求示例见[调用 Agent 服务](../examples/README.md#调用-agent-服务)。该示例只处理本次请求最后一条用户文本，不保存跨轮历史。

## 添加 Memory 或使用原生模型

Memory 通过 `ChatClient.builder(model).defaultAdvisors(new MemoryAdvisor(memory, true))` 接入；`true` 表示显式开启本轮对话回写。上下文构造和完整调用见 [MemoryAdapters.spring](../examples/src/main/java/example/MemoryAdapters.java) 与 [Memory 指南](../examples/README.md#为框架添加-memory)。

Advisor 属于 `ChatClient` 的执行链；上面的 `SpringAIEvents.stream(model, prompt)` 使用自身的执行链，不会自动执行另一个 `ChatClient` 上注册的 Advisor。两个示例展示不同入口，不要把注册 Advisor 理解为修改了底层模型的全局行为。

已有原生 Spring AI 模型时，可以直接用它代替托管模型，再注册 AgentCore 工具，见 [NativeFrameworkAgents.spring](../examples/src/main/java/example/NativeFrameworkAgents.java)。供应商配置与认证由应用负责。

AG-UI 支持完整消息边界、工具调用与结果；OpenAI Chat Completions 仅输出其协议支持的文本与工具调用，不能完整表达 Agent 内部所有中间事件。
