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

在 IDE 中以 Maven 项目导入仓库，或将所需示例复制到已添加对应模块依赖的应用中。`CloudResources`、`DirectResources` 和各框架 Agent 示例提供 `main()` 入口；`MemoryAdapters`、`NativeFrameworkAgents` 等提供供应用调用的接入方法。

## 框架依赖

各适配模块会引入对应的框架依赖，不需要安装其他框架模块：

| 框架 | artifactId | 框架版本 |
| --- | --- | --- |
| Spring AI | `agentcore-sdk-spring-ai` | 2.0.1 |
| LangChain4j | `agentcore-sdk-langchain4j` | 1.20.0 |
| AgentScope Java | `agentcore-sdk-agentscope` | 2.0.3 |

模块坐标见[安装说明](../README.md#安装)。提供 HTTP 服务时，还需要 `agentcore-sdk-server`。

自有供应商模型优先使用所选框架的原生 Provider，并按需安装对应依赖。`NativeFrameworkAgents` 接收已配置好的原生模型，不经过 `core.model()` 或 `core.directModel()`；LangChain4j 和 AgentScope 不需要 Spring AI。供应商认证、超时和模型生命周期由应用管理。需要记忆时，按 `MemoryAdapters` 注册相同的框架适配器，替换模型不会改变记忆接口。这些示例按次执行，不提供跨轮历史存储。

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
