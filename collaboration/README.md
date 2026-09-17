# Java 协作模块

`agentcore-collaboration` 是独立 Maven 模块，只依赖基础 `agentcore-sdk`。使用者自行注册协作工具，不要求使用 `AgentCoreServer`，也不会自动启动 Matrix 轮询或替换业务 Agent 的身份。

协作能力按白名单开放，需先在 AgentCore 平台开通。安装模块不会自动开通平台协作能力。

协作模块可以独立安装和升级，不要求同时升级基础 SDK。

提供 24 个 Worker 工具，包括团队上下文、任务/子任务查询、确认/进度/心跳/阻塞/结果提交、任务文件上传下载、结果/事件历史和 Team 文件同步。应用主动启用协作后，工具注册和提示词组合不依赖当前团队状态；团队成员关系在实际调用工具时检查，没有团队时返回结构化失败结果，不会阻止普通 Agent 的构建。普通问候不应触发任务查询。

使用 `composePrompt(userPrompt)` 保留业务提示词并追加协作规则和工作目录说明，与 Python 的 `compose_prompt()` 行为一致。不需要业务代码手工拼接 `WORKER_PROMPT` 或判断是否有团队。

内置 `task-execution` 和 `file-sharing` 两个 Skill，按需通过 `skills()` 加载，不会自动注入用户 Agent。它们随协作包打包，运行时解压至该实例独占的临时目录，关闭实例时清理。

```java
import io.agentcore.collaboration.Collaboration;
import io.agentcore.collaboration.CollaborationContext;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import dev.langchain4j.service.AiServices;

var workspace = java.nio.file.Path.of("/app/workspace"); // 提前创建，选择允许 Agent 处理文件的目录。
var collaboration = Collaboration.auto(workspace); // 应用生命周期结束时 close()。

// 启动阶段注册一次；不要在 Reactor 事件线程上执行这些阻塞初始化。
var tools = new java.util.ArrayList<>(collaboration.tools().block());
tools.addAll(io.agentcore.skill.Skill.tools(collaboration.skills().block(), workspace));
var assistant = AiServices.builder(Assistant.class)
    .streamingChatModel(model)
    .tools(AgentCoreLangChain4j.tools(tools))
    .systemMessageProvider(id -> collaboration.composePrompt("你是应用助手。"))
    .build();

// 每次请求只传调用上下文。incomingHeaders 来自应用已验证的可信平台入口。
var context = CollaborationContext.fromHeaders(incomingHeaders);
var metadata = context == null ? java.util.Map.<String, Object>of()
    : java.util.Map.<String, Object>of(CollaborationContext.KEY, context);
var parameters = dev.langchain4j.invocation.InvocationParameters.from(metadata);
var events = io.agentcore.langchain4j.LangChain4jEvents.from(() -> assistant.chat(input, parameters));
```

`Assistant` 是应用的 LangChain4j AiServices 接口，`model` 由框架适配器构建：

```java
interface Assistant {
    dev.langchain4j.service.TokenStream chat(
        @dev.langchain4j.service.UserMessage String message,
        dev.langchain4j.invocation.InvocationParameters parameters);
}
```

Spring AI 和 AgentScope Java 注册相同的 `Tool`，分别使用框架原生调用上下文。不把上下文放入工具 Schema、提示词或共享对象字段，也不需要 `ThreadLocal`。

### Spring AI

```java
// 启动时创建，可复用。
var callbacks = tools.stream().map(io.agentcore.springai.AgentCoreSpringAI::tool)
    .toArray(org.springframework.ai.tool.ToolCallback[]::new);
var chatClient = org.springframework.ai.chat.client.ChatClient.builder(model)
    .defaultTools(callbacks)
    .defaultSystem(collaboration.composePrompt("你是应用助手。"))
    .build();

// metadata 与上例相同，每次请求单独创建。
var request = chatClient.prompt().user(input).toolContext(metadata);
var response = request.call().content();
// 流式调用使用 request.stream().content()，两种方式均传递上下文。
```

### AgentScope Java

```java
// 工具不绑定请求；Toolkit 可用作 Agent 的构建模板。
var toolkit = new io.agentscope.core.tool.Toolkit();
tools.forEach(tool -> toolkit.registerAgentTool(io.agentcore.agentscope.AgentCoreAgentScope.tool(tool)));

// 每次调用创建独立 RuntimeContext，agent 是应用管理的 ReActAgent。
var runtimeContext = io.agentscope.core.agent.RuntimeContext.builder()
    .sessionId(sessionId).putAll(metadata).build();
var response = agent.call(input, runtimeContext);
// 流式调用：agent.streamEvents(input, runtimeContext)。
```

Agent 的生命周期与会话状态由应用按框架约束管理；协作不要求每个请求重新注册工具或创建 Agent。复用 Agent 不代表可以并发修改同一会话状态。

完整可编译示例见 [CollaborationAgent.java](../examples/src/main/java/example/CollaborationAgent.java)，可以由应用自己的 HTTP 或消息入口调用。

这个上下文只是路由信息，不证明调用方身份。任务写操作要求 Task Room 上下文，并检查子任务所属房间；最终授权仍由任务服务决定。SDK 不在不确定的写失败后自动重试。

未启用协作、团队不可用、非 Worker 角色、缺少请求上下文或任务房间不匹配等预期前置条件失败，返回 `{"ok": false, "code": "COLLABORATION_...", "retryable": false, "message": "..."}`，让模型能够解释工具不可用并继续回答，不执行被拒绝的操作。

Task Service 明确返回 HTTP 错误时也转换为工具结果：401/403 为 `TASK_UNAUTHORIZED`，400/422 为 `TASK_INVALID`，404 为 `TASK_NOT_FOUND`，409 为 `TASK_CONFLICT`，413 为 `FILE_TOO_LARGE`，其他状态为 `TASK_UNAVAILABLE`（统一加 `COLLABORATION_` 前缀）。只有不可用结果标记 retryable，不表示 SDK 自动重试；任何操作都不因该标记自动重放。结果不包含原始响应正文。此处理只在协作模块内生效；程序错误、配置读取错误和未取得 HTTP 响应的传输异常仍向上传播，不在通用框架适配器中兜底捕获。

团队配置在工具调用时按需重读；文件删除代表停用，非法更新保留最后一份有效配置并记录警告。已注册工具可继续复用，每次执行使用该次调用传入的上下文。未传上下文时不会继承上次值，要求 Task Room 的写操作会拒绝执行。

## 文件操作

- Task/Subtask 文件：先用 list/read 工具取得输入；二进制文件可通过 download 工具保存到工作目录。上传使用 inline UTF-8/Base64 内容或 `local_path`，结果提交引用上传接口返回的 `fileRef`。
- Team 文件：`agentteams_filesync_push/pull/list/stat` 操作当前 Team 的 `shared/` 空间，支持单文件和目录。Team 由可信请求上下文选取，不作为模型可随意设置的参数。
- 本地路径相对于 `workspace`，不允许越界或符号链接。下载先写临时文件，成功后替换目标；不会把半个文件留在目标路径。`overwrite=false` 可拒绝覆盖，默认允许覆盖。目录同步不删除未列出的本地或远端文件。
- 下载签名 URL 仅在 SDK 内部消费，不返回给模型，也不附带 Matrix Token。文件上传使用 Task Service 的 multipart PUT 接口，不重试不确定的写操作。

此模块不负责接收 Matrix 消息、唤醒应用、维持 Agent 对话历史或替代服务端权限校验。可以使用自己的 HTTP 服务、消息消费入口或 AgentCoreServer；在每次请求中传入框架原生上下文即可。手动调用工具时使用 `tool.call(arguments, metadata)`。使用方负责等在途请求结束后再关闭资源。

此模块提供 Worker 能力，不包含 Manager 的任务创建、分配和审批工具。
