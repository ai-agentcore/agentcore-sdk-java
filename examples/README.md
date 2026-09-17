# Java 示例

代码在 `src/main/java/example`，随根目录 `mvn verify` 一起编译，不会在构建时访问云端或启动服务。

| 示例 | 内容 |
| --- | --- |
| `CloudResources` | 托管 Model、MCP、Skill、Memory |
| `DirectResources` | 自有模型与 MCP |
| `LangChainAgent` | LangChain4j Agent、MCP/Skill 工具、AG-UI/OpenAI 服务 |
| `SpringAIAgent` | Spring AI 工具循环与完整执行事件 |
| `AgentScopeAgent` | AgentScope Java 工具与执行事件 |
| `MemoryAdapters` | 三种框架检索记忆，可选自动回写本轮对话；读写 Scope 分离 |
| `NativeFrameworkAgents` | 使用三个框架各自的原生模型，接入 AgentCore 工具与执行事件 |
| `ProviderModels` | 可选的 Spring AI 多供应商模型与 Embedding 便捷入口，不注入平台身份 |
| `ServerExtensions` | 独立 Agent 执行入口、自定义协议路由、保留内置协议与就绪检查 |
| `CollaborationAgent` | 独立协作工具接入应用入口，不依赖 AgentCoreServer |

资源名称都是占位名称，运行前替换为自己 Workspace 的资源。云端示例在 AgentCore 托管环境中运行；构建示例不需要任何凭证。自有模型示例读取应用自己的 `MODEL_API_KEY`，不要硬编码真实凭证。

服务示例默认监听 8080；`/agui` 为 AG-UI，`/openai/v1/chat/completions` 为 OpenAI Chat Completions，`/healthz` 为存活检查，`/readyz` 为就绪检查。示例不实现用户认证与跨轮历史存储，需要应用自行接入。

不需要 `AgentCoreServer` 时，可直接订阅框架事件流，或只将 `Tool` 转为框架工具注册。协作的独立工具用法见 [协作模块](../collaboration/README.md)。

自有供应商模型优先使用所选框架的原生 Provider，并按需安装对应依赖。`NativeFrameworkAgents` 接收已配置好的原生模型，不经过 `core.model()` 或 `core.directModel()`；LangChain4j 和 AgentScope 不需要 Spring AI。供应商认证、超时和模型生命周期由应用管理。需要记忆时，按 `MemoryAdapters` 注册相同的框架适配器，替换模型不会改变记忆接口。这些示例按次执行，不提供跨轮历史存储。

多个 Skill 不应直接拼接各自的 `tools()`（会产生同名工具）。使用 `Skill.tools(skills, workingDirectory)` 生成一组按 Skill 名称分发的工具，并指定命令执行目录。仅加载一个 Skill 时，`skill.tools()` 默认在该 Skill 根目录执行命令。命令具备应用进程权限，不是安全沙箱；设置 `ALLOW_EXECUTE_COMMAND=false` 可不暴露命令工具。
