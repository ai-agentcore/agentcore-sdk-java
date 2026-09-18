# 集成 LangChain4j

使用 `AiServices` 创建 Agent，将 AgentCore 的托管模型、MCP 和 Skill 接入 LangChain4j 的工具调用流程。

## 添加依赖

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk-langchain4j</artifactId>
  <version>0.1.0</version>
</dependency>
```

该模块包含基础 SDK 和 LangChain4j 1.20.0 依赖，不需要 Spring AI。以下 HTTP 服务示例还需添加：

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk-server</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 接入模型和工具

先在类中定义流式 Agent 接口：

```java
public interface Assistant { TokenStream chat(String input); }
```

在 Workspace 中准备模型连接、支持工具调用的模型、MCP 和 Skill。以下片段放在 `try (var core = AgentCore.auto())` 中，在 AgentCore 托管环境运行；替换占位资源名称。

```java
var model = AgentCoreLangChain4j.streamingModel(core.model("my-connection", "my-model").block());
var tools = new ArrayList<Tool>(core.mcp("my-mcp").block().listTools().block());
tools.addAll(core.skills().load("my-skill").block().tools());
var assistant = AiServices.builder(Assistant.class)
    .streamingChatModel(model)
    .tools(AgentCoreLangChain4j.tools(tools))
    .build();
```

`streamingModel(...)` 提供流式模型，普通调用可使用 `model(...)`。`tools(...)` 同时注册工具描述和执行函数。不使用 Skill 时，移除对应加载行即可。

## 输出执行事件并提供服务

```java
try (var server = new AgentCoreServer(request -> LangChain4jEvents.from(() -> assistant.chat(
        Json.text(request.messages().get(request.messages().size() - 1).get("content"), "user content"))))
        .start(8080)) {
    server.await();
}
```

`LangChain4jEvents.from(...)` 管理 `TokenStream` 的回调和启动，将文本、工具调用、结果及消息边界转换为通用事件。传入尚未启动的流，不要再手动调用 `start()`，也不要只转发 `onPartialResponse` 中的文本。

完整可编译代码及 imports 见 [LangChainAgent.java](../examples/src/main/java/example/LangChainAgent.java)。构建、启动命令见[示例指南](../examples/README.md#准备资源与运行示例)，AG-UI/OpenAI 请求示例见[调用 Agent 服务](../examples/README.md#调用-agent-服务)。该示例未配置 ChatMemory，只处理本次请求最后一条用户文本。

## 添加 Memory 或使用原生模型

创建 `MemoryAdapter(memory, true)`，将 `adapter.retriever()` 注册到 `AiServices.contentRetriever(...)`，再通过 `adapter.call(input, () -> assistant.chat(input))` 执行普通调用。`true` 表示显式开启本轮对话回写；完整上下文和调用见 [MemoryAdapters.langchain4j](../examples/src/main/java/example/MemoryAdapters.java) 与 [Memory 指南](../examples/README.md#为框架添加-memory)。

流式调用使用 `adapter.stream(input, () -> assistant.chat(input))` 替代 `LangChain4jEvents.from(...)`。它已经包含执行事件转换和回写，不要再包装一次转换器或重复保存对话。仅注册 retriever 能检索记忆，但不会自动回写。

已有原生 LangChain4j 模型时，可以直接用它代替托管模型，再注册 AgentCore 工具，见 [NativeFrameworkAgents.langchain4j](../examples/src/main/java/example/NativeFrameworkAgents.java)。供应商配置与认证由应用负责。

AG-UI 保留工具结果和多轮消息边界；OpenAI Chat Completions 不提供对应的完整表达，不要根据响应正文猜测过程消息或最终答案。
