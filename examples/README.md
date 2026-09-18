# AgentCore Java SDK 示例指南

代码在 `src/main/java/example`，随根目录 `mvn verify` 一起编译，不会在构建时访问云端或启动服务。

| 示例 | 内容 |
| --- | --- |
| [CloudResources](src/main/java/example/CloudResources.java) | 托管 Model、MCP、Skill、Memory |
| [DirectResources](src/main/java/example/DirectResources.java) | 自有模型与 MCP |
| [LangChainAgent](src/main/java/example/LangChainAgent.java) | LangChain4j Agent、MCP/Skill 工具、AG-UI/OpenAI 服务 |
| [SpringAIAgent](src/main/java/example/SpringAIAgent.java) | Spring AI 工具循环与完整执行事件 |
| [AgentScopeAgent](src/main/java/example/AgentScopeAgent.java) | AgentScope Java 工具与执行事件 |
| [MemoryAdapters](src/main/java/example/MemoryAdapters.java) | 三种框架检索记忆，可选自动回写本轮对话；读写 Scope 分离 |
| [NativeFrameworkAgents](src/main/java/example/NativeFrameworkAgents.java) | 使用三个框架各自的原生模型，接入 AgentCore 工具与执行事件 |
| [ProviderModels](src/main/java/example/ProviderModels.java) | Spring AI 多供应商模型与 Embedding |
| [ServerExtensions](src/main/java/example/ServerExtensions.java) | 自定义协议路由与就绪检查 |

## 准备资源与运行示例

资源名称都是占位名称，运行前替换为自己 Workspace 的资源。云端示例在 AgentCore 托管环境中运行；构建示例不需要任何凭证。自有模型示例读取应用自己的 `MODEL_API_KEY`，不要硬编码真实凭证。

在 IDE 中以 Maven 项目导入仓库，或将所需示例复制到已添加对应模块依赖的应用中。`CloudResources`、`DirectResources`、`MemoryAdapters` 和各框架 Agent 示例提供 `main()` 入口；`NativeFrameworkAgents`、`ProviderModels` 提供供应用调用的接入方法。

### 1. 选择示例并替换资源名称

| 示例 | 运行前需要替换的内容 |
| --- | --- |
| `SpringAIAgent` / `AgentScopeAgent` | `my-connection`、`my-model`、`my-mcp` |
| `LangChainAgent` | 上述三项，以及 `my-skill` |
| `MemoryAdapters` | `my-connection`、`my-model`、`my-memory-store`，以及示例用户、Agent 和会话标识 |
| `CloudResources` | 模型连接、模型、MCP、Skill 名称及版本、MemoryStore 名称 |
| `DirectResources` | 自有模型名称、模型地址、MCP 地址；通过环境变量提供 `MODEL_API_KEY` |
| `ServerExtensions` | 无需云端资源，返回固定文本，用于验证 HTTP 服务启动与协议调用 |

模型连接名称与模型名称是两个不同参数。请选择支持工具调用的模型来运行框架工具示例。只需模型时，可以移除示例中的 MCP/Skill 加载及工具注册代码，不必为未使用的功能创建资源。

### 2. 构建示例

在仓库根目录执行（JDK 17+、Maven 3.9+）：

```bash
mvn -B -ntp -f examples/pom.xml clean package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime
```

这会编译全部示例，并从 Maven 解析已发布的 SDK 依赖。输出位于 `examples/target/classes` 和 `examples/target/dependency`，不调用云端服务。示例工程为了编译所有案例而引入多个适配模块；自己的应用只需添加所用框架的模块。

### 3. 启动一个示例

以下命令在仓库根目录执行，适用于 Linux/macOS（Windows 将 classpath 中的 `:` 改为 `;`）。先用无需云资源的固定响应服务验证启动方式：

```bash
java -cp 'examples/target/classes:examples/target/dependency/*' example.ServerExtensions
```

另开终端执行 `curl http://localhost:8080/healthz`，再按[调用 Agent 服务](#调用-agent-服务)发送 AG-UI 或 OpenAI 请求。停止该进程后，再选择一个框架示例；这些服务都使用 8080 端口，不要同时启动：

```bash
# 三选一；托管资源示例需要在 AgentCore 托管环境中运行。
java -cp 'examples/target/classes:examples/target/dependency/*' example.SpringAIAgent
java -cp 'examples/target/classes:examples/target/dependency/*' example.LangChainAgent
java -cp 'examples/target/classes:examples/target/dependency/*' example.AgentScopeAgent
```

云端运行时，镜像需要包含 JRE 17+、上述 classes 和 dependency 目录，并使启动命令中的路径与工作目录一致；服务端口配置为 8080，存活检查路径为 `/healthz`。构建成功不代表已经获得云端资源的访问权限。

示例工程未绑定日志实现，直接运行可能提示 `No SLF4J providers were found`。这不会阻止服务启动；需要 SDK 诊断日志时，请在应用中添加并配置 Logback 等 SLF4J 实现。

## 框架依赖

各适配模块会引入对应的框架依赖，不需要安装其他框架模块：

| 框架 | artifactId | 框架版本 |
| --- | --- | --- |
| [Spring AI](../docs/spring-ai.md) | `agentcore-sdk-spring-ai` | 2.0.1 |
| [LangChain4j](../docs/langchain4j.md) | `agentcore-sdk-langchain4j` | 1.20.0 |
| [AgentScope Java](../docs/agentscope.md) | `agentcore-sdk-agentscope` | 2.0.3 |

模块坐标见[安装说明](../README.md#安装)。提供 HTTP 服务时，还需要 `agentcore-sdk-server`。

自有供应商模型优先使用所选框架的原生 Provider，并按需安装对应依赖。`NativeFrameworkAgents` 接收已配置好的原生模型，不经过 `core.model()` 或 `core.directModel()`；LangChain4j 和 AgentScope 不需要 Spring AI。供应商认证、超时和模型生命周期由应用管理。需要记忆时，按 `MemoryAdapters` 注册相同的框架适配器，替换模型不会改变记忆接口。这些示例按次执行，不提供跨轮历史存储。

## 为框架添加 Memory

在 Workspace 中创建 MemoryStore。此示例只需模型连接、模型和 MemoryStore，不需要 MCP 或 Skill。

### 创建请求对应的记忆上下文

以下代码在 `try (var core = AgentCore.auto())` 中使用；完整 imports、三个框架的注册方式与运行入口见 [MemoryAdapters.java](src/main/java/example/MemoryAdapters.java)。

```java
var model = core.model("my-connection", "my-model").block();
var store = core.memory("my-memory-store");
var readScope = new MemoryScope("example-user", "example-agent", null);
var writeScope = new MemoryScope("example-user", "example-agent", "example-session");
var memory = new MemoryContext(store, readScope, writeScope, 5);
String answer = MemoryAdapters.langchain4j(model, memory, "我喜欢简短的中文回答。").block();
```

读范围省略 `sessionId`，用于检索同一用户、同一 Agent 的跨会话记忆；写范围包含当前会话标识。最后一个参数 `5` 是检索条数。真实应用应从已认证的请求上下文确定这些标识，不要把示例中的固定用户标识用于所有请求。

### 选择框架运行一轮对话

修改示例资源名称后，执行前面的构建命令，再选择一个框架运行。该示例处理一轮对话后退出，不启动 HTTP 服务：

```bash
java -cp 'examples/target/classes:examples/target/dependency/*' example.MemoryAdapters spring-ai
java -cp 'examples/target/classes:examples/target/dependency/*' example.MemoryAdapters langchain4j
java -cp 'examples/target/classes:examples/target/dependency/*' example.MemoryAdapters agentscope
```

| 框架 | 检索接入 | 回写接入 |
| --- | --- | --- |
| Spring AI | 为 `ChatClient` 注册 `MemoryAdvisor` | Advisor 在调用完成后回写 |
| LangChain4j | 为 `AiServices` 注册 `adapter.retriever()` | 使用 `adapter.call(...)`；流式执行使用 `adapter.stream(...)` |
| AgentScope Java | 为 `ReActAgent` 注册 `MemoryMiddleware` | Middleware 在调用完成后回写 |

示例中的三个适配器均显式传入 `true`，会写入本轮用户输入和正常完成的最终文本答案。只检索、不回写时，使用不带布尔参数的构造函数。不要在自动回写之外再手动保存同一轮对话。

新记忆不是立即可检索的；首次查询为空不能单独说明写入失败。适配器遇到 SDK 记忆服务错误时会记录诊断日志并继续对话，因此“模型有回答”也不等于“记忆写入成功”。应用需配置 SLF4J 日志实现，并检查记忆操作日志或后续查询结果。直接调用 `MemoryStore` 的错误仍会向调用者抛出。

长期记忆不替代框架的会话历史。此示例不会恢复完整历史消息，也不将历史消息或工具结果重复回写。

## 使用 Skill

云端 Skill 使用 `core.skills().load("my-skill", "1.0.0")` 加载；省略版本时解析最新版本。同一 Core 实例固定首次成功加载的版本，使用新实例获取更新版本。

本地 Skill 可用 `core.skills().local(Path.of("./skills"))` 加载一个 Skill 或其子目录中的多个 Skill。每个 Skill 需要 `SKILL.md`，包含名称、描述与使用说明。

多个 Skill 不应直接拼接各自的 `tools()`（会产生同名工具）。使用 `Skill.tools(skills, workingDirectory)` 生成一组按 Skill 名称分发的工具，并指定命令执行目录。仅加载一个 Skill 时，`skill.tools()` 默认在该 Skill 根目录执行命令。命令具备应用进程权限，不是安全沙箱；设置 `ALLOW_EXECUTE_COMMAND=false` 可不暴露命令工具。

## 调用 Agent 服务

在 AgentCore 部署并启动 `example.LangChainAgent`，使用应用可访问的服务地址调用。以下以 `http://localhost:8080` 为例；该地址适用于在容器内执行命令或已建立本地端口转发的场景。

服务示例监听 8080；`/healthz` 为存活检查，`/readyz` 为就绪检查。示例不实现用户认证与跨轮历史存储，需要应用自行接入。

### AG-UI

```bash
curl -N http://localhost:8080/agui \
  -H 'Content-Type: application/json' \
  -d '{
    "threadId": "example-thread",
    "runId": "example-run",
    "messages": [{"id": "message-1", "role": "user", "content": "你好"}]
  }'
```

响应为 SSE，包含运行开始、消息和运行结束事件；Agent 调用工具时还会输出工具调用与结果。`threadId` 和 `runId` 必须非空，每次执行应使用新的 `runId`。

### OpenAI Chat Completions

```bash
curl -N http://localhost:8080/openai/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "my-agent",
    "stream": true,
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

响应为 OpenAI Chat Completions 流式 chunk，以 `data: [DONE]` 结束。将 `stream` 改为 `false` 可获取普通 JSON 响应。示例使用代码中配置的模型连接；请求中的 `model` 不会自动切换底层模型。

两个 SSE 接口均提供 15 秒空闲心跳。展示完整工具执行过程时使用框架事件转换器；AG-UI 保留消息边界和工具结果，OpenAI Chat Completions 只表达协议支持的文本和工具调用。

不需要 `AgentCoreServer` 时，可以在自己的服务中消费框架事件流，并按需要的协议编码响应。自建服务负责入口鉴权、会话历史及请求取消处理。
