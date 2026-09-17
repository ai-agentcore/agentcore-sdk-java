# AgentCore SDK for Java

使用 Java 构建 Agent，接入 AgentCore 云端资源和已有 Agent 框架。

[AgentCore 官网](https://www.aliyun.com/product/agentcore) · [官方文档](https://help.aliyun.com/zh/agentcore/) · [示例](examples/README.md)

> 当前为本地开发版 `0.1.0-SNAPSHOT`，尚未发布 Maven Central，也未完成云端端到端验证。下述 Maven 坐标用于本地构建，不代表已公开发行。

## 环境与模块

需要 JDK 17+、Maven 3.9+。基础 API 返回 Reactor `Mono` / `Flux`；普通 Java 程序可在非事件循环线程使用 `block()`。

| Maven artifactId | 用途 |
| --- | --- |
| `agentcore-sdk` | Model、MCP、Skill、Memory、Credential |
| `agentcore-sdk-server` | AG-UI、OpenAI Chat Completions HTTP 服务 |
| `agentcore-sdk-spring-ai` | Spring AI 2.0.1 |
| `agentcore-sdk-langchain4j` | LangChain4j 1.20.0 |
| `agentcore-sdk-agentscope` | AgentScope Java 2.0.3 |
| `agentcore-collaboration` | 独立协作模块，按需使用 |

框架与协作不是基础包的默认依赖。各模块可以分别发布；协作模块只依赖基础 SDK，不依赖 Server 或任何 Agent 框架。当前 Maven `groupId` 为 `io.github.ai-agentcore`，正式发布前需要确认该命名空间的发布权限。

```bash
mvn verify
mvn install -DskipTests
```

应用按需声明依赖，例如：

```xml
<dependency>
  <groupId>io.github.ai-agentcore</groupId>
  <artifactId>agentcore-sdk-langchain4j</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 云端资源

以下代码在部署到 AgentCore 的应用中运行。提前在当前 Workspace 中准备所需资源，并为 Agent 配置访问权限；只使用模型时不需要创建 MCP 或 Skill。

```java
import io.agentcore.AgentCore;
import java.util.List;
import java.util.Map;

try (var core = AgentCore.auto()) {
    var model = core.model("my-connection", "my-model").block();
    var response = model.completion(List.of(Map.of("role", "user", "content", "你好"))).block();

    var mcp = core.mcp("my-mcp").block();
    var tools = mcp.listTools().block();

    var skill = core.skills().load("my-skill", "1.0.0").block();
    var skillTools = skill.tools();
}
```

资源解析发生在创建托管资源客户端时。应保留并复用已创建的客户端，不要在每轮对话中重新解析资源。配置在首次需要时读取；读取成功后保持快照。关闭 `AgentCore` 会关闭其 MCP Session 并清理 SDK 物化的 Skill 目录，不会删除用户的本地 Skill。

显式设置 `workspaceId`／`regionId` 时，控制面访问使用应用提供的 `accessKeyCredential`，不读取运行时 YAML 或 env 文件，也不使用其中的 Controller 凭证；不能同时设置 `configPath`／`envPath`。这种方式可访问 Skill、Memory 等控制面资源，但不能凭空获得托管 Model／MCP 所需的网关配置与 Consumer 凭证。需要这些资源时，使用统一的运行时配置来源。

文件尚未挂载时默认等待最多 10 秒（`AGENTCORE_CONFIG_WAIT_TIMEOUT`，单位秒），每 500 毫秒检查一次；格式错误立即报错，修正后可重新调用，不保留失败的半初始化状态。Controller 配置优先使用 `AGENTCORE_` 名称，兼容旧 `AGENTTEAMS_` 名称；同名值以运行时 env 文件优先于进程环境变量。SA Token 每次换取凭证时重新读文件。托管文件模式的控制面 Endpoint 优先级为显式 `controlPlaneEndpoint` → env 文件 `AGENTCORE_CONTROL_ENDPOINT` → 同名进程环境变量 → Region 默认地址。

### Bootstrap token 启动

控制台为应用签发 bootstrap token 后，可以通过环境变量 `AGENTCORE_DEBUG_TOKEN` 配置。也可将应用从安全配置中取得的值传入 `AgentCore.builder().bootstrapToken(token).build()`，不要将真实 token 写入源码。

配置了非空 token 时优先使用 token 链路，不读取本地 `agent.yaml` 或 env 文件；token 无效会报错，不静默回退本地配置。未配置时保留原有托管文件启动方式。

SDK 用 bootstrap JWT 向 Controller 换取 SA，再用 SA 获取控制配置 OSS 的 STS，将配置读入内存。Workspace／Region 与 Consumer Header 来自该配置；Model／MCP 的网关地址使用 token 的 `modelGatewayUrl`，保留配置中对应资源路径。控制面域名仍可由 `controlPlaneEndpoint` 或 `AGENTCORE_CONTROL_ENDPOINT` 指定。不要同时配置 bootstrap token 和显式 `workspaceId`／`regionId`。

SA 按需更新，JWT 在到期前由进程内后台任务续期，即使应用暂时没有请求也会执行。新的 JWT 和 SA 仅保存在内存，不修改环境变量或文件，不跨重启；关闭 `AgentCore` 会停止续期。平台必须支持对应 token 签发和续期接口，不能把任意 API Key 当作 bootstrap token。

未显式指定控制面 Endpoint 时，读操作遇到连接故障可尝试一次同 Region 私网地址，成功后复用该地址；显式地址和写操作不自动切换。Skill 的 OSS 下载可在网络故障或服务端 5xx 时尝试私网地址，包含 Host 签名约束的 URL 不改写。不会跟随重定向传播认证信息。

托管模型根据模型连接的协议使用 OpenAI 或 Anthropic。`completion()` 和 `stream()` 用于聊天；OpenAI 协议另外提供 `responses()` / `responsesStream()`。网关有路由不代表模型支持该操作，能力错误由服务端返回，不转换为其他 API 或伪造流式响应。

`model.descriptor()` 返回控制面提供的模型连接、协议、模型 ID/名称、`contextSize`、`maxTokens` 和 `capabilities`。未下发的可选数值为 `null`，不依靠本地模型目录猜测；直连模型没有托管描述信息，返回 `null`。模型调用不因这些元数据自动截断输入或改写请求。

### Skill

同一个 `AgentCore` 实例中，按名称和请求版本固定首次成功加载的 Skill；未指定版本时只在首次加载解析 latest。并发加载共享解析与下载，失败后可重试。使用新实例获取更新版本，不自动替换 Agent 正在使用的文件。

通过 `AgentCore.builder().skillWorkspaceDir(path)` 或 `AGENTCORE_SKILL_WORKSPACE_DIR` 指定物化工作目录。SDK 在其下创建独立子目录，关闭时只清理自己的子目录，不删除用户目录；这不是跨进程持久缓存。本地 Skill 可用 `core.skills().local(path)` 加载一个 Skill 或其子目录中的多个 Skill，读取 `SKILL.md` 的 name、description 和正文。

`skill.tools()` 提供加载、读文件和执行命令工具。需要应用审批时，使用 `skill.tools((command, cwd) -> approve(command, cwd), 300)`；`approve` 是应用自己的同步审批函数，返回 false 时不会执行命令。默认命令超时 300 秒，可由工具参数覆盖。命令使用应用进程权限，不是安全沙箱；`ALLOW_EXECUTE_COMMAND=false` 可禁用命令工具。多个 Skill 使用 `Skill.tools(skills, workingDirectory)`，不要直接拼接同名工具。

## 自定义资源

直连资源不需要平台配置。凭证从应用自己的配置或凭证服务获取，不写入源码。

```java
import io.agentcore.model.ModelClient;
import io.agentcore.mcp.MCPClient;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.Map;

var model = core.directModel("my-model", URI.create("https://models.example.com/v1"),
    ModelClient.Protocol.OPENAI,
    () -> Mono.just(Map.of("Authorization", "Bearer " + System.getenv("MODEL_API_KEY"))));

var mcp = core.directMcp(URI.create("https://tools.example.com/mcp"),
    MCPClient.Transport.STREAMABLE_HTTP, () -> Mono.just(Map.of()));
```

Anthropic 的 `baseUrl` 不包含末尾 `/v1`。直连 MCP 还支持 SSE 和 `stdioMcp(command, args, environment)`。直连 OpenAI 兼容的向量模型可使用 `embedding()`；托管模型入口不提供 Embedding。

### 多供应商模型

使用 Agent 框架时，优先直接使用该框架的原生 Provider，不必转换成 AgentCore 的 `ModelClient`：

| 框架 | 自有模型入口 | AgentCore 负责 |
| --- | --- | --- |
| Spring AI | 原生 `ChatModel`／`EmbeddingModel` | 工具、Memory 和执行事件适配 |
| LangChain4j | 原生 `ChatModel`／`StreamingChatModel` | 工具、Memory 和执行事件适配 |
| AgentScope Java | 原生 `Model` | 工具、Memory 和执行事件适配 |

应用按需安装并配置对应 Provider。LangChain4j 和 AgentScope 不依赖 Spring AI，也不经过 Spring AI 转发。托管资源仍由 AgentCore 解析和鉴权；自有模型的认证、超时、重试和生命周期由应用及其 Provider 管理，不注入平台 Consumer 凭证。完整接入示例见 [NativeFrameworkAgents.java](examples/src/main/java/example/NativeFrameworkAgents.java)。

没有使用 Agent 编排框架、但希望调用 Ollama、Gemini、Bedrock 等供应商时，也可以选择 `agentcore-sdk-spring-ai` 的便捷入口；这不是其他框架的通用后端：

```java
// providerChatModel / providerEmbeddingModel 由应用使用 Spring AI 配置。
var client = AgentCoreSpringAI.directModel(providerChatModel, providerEmbeddingModel);
var response = client.completion(new org.springframework.ai.chat.prompt.Prompt("你好")).block();
var chunks = client.stream(new org.springframework.ai.chat.prompt.Prompt("你好"));
var events = client.events(new org.springframework.ai.chat.prompt.Prompt("你好"));
```

例如使用 Ollama 时额外安装 `org.springframework.ai:spring-ai-ollama:2.0.1`，通过 `OllamaApi.builder().baseUrl(...)`、`OllamaChatModel.builder().ollamaApi(api).options(...)` 创建原生模型后传入即可；本地协议测试包含这个 Provider。其他供应商参见 [Spring AI 模型接口](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)。

`completion()`／`stream()` 保留 Spring AI 原生 `ChatResponse`，`embedding()` 使用其原生请求和响应；`events()` 使用框架工具循环，并输出 SDK 通用执行事件，可交给 AG-UI/OpenAI 服务层。供应商的多模态、工具调用和选项支持取决于具体 Provider，不伪造 OpenAI 响应或 Responses API。该入口不读取平台配置、不注入平台认证、不增加额外重试；超时、Provider 自身的重试及资源关闭由配置原生模型的应用负责。

该便捷入口的示例见 [ProviderModels.java](examples/src/main/java/example/ProviderModels.java)。

MCP 连接超时 10 秒、初始化 30 秒、工具发现 60 秒、工具执行 10 分钟。成功建连后复用 Session；传输失败后失效连接，下一次调用重新建连，不重放失败的工具调用。取消某个并发调用不影响其他调用；所有建连等待者取消或关闭客户端时会清理未完成的建连。

## Credential 与 MCP Header

```java
var credential = core.credentials().get("my-api-key").block();
// 仅交给目标服务的客户端，不打印 credential.value()。

var mcp = core.mcp("my-mcp", "my-header-credential", Map.of("X-App", "example")).block();
```

MCP Header 凭证会校验类型和应用范围；凭证 Header 与显式 Header 不能覆盖平台认证及 MCP 协议保留字段，大小写变体同样受限制。Header 名称按大小写不敏感处理；显式 Header 与凭证 Header 同名也会报错，不静默覆盖。

MCP Header 作用于客户端 Session，在建立连接时解析并固定，不能将不同用户身份写入共享客户端。此版本未提供按 `callTool()` 请求隔离 Header 的 API。

## Memory

```java
import io.agentcore.memory.MemoryScope;
import io.agentcore.memory.MemoryContext;

var memory = core.memory("my-memory-store");
var scope = new MemoryScope("user-123", "my-agent", "session-456");
var matches = memory.searchMemories("用户的偏好", scope, 5).block();
memory.addMemories("用户更喜欢简短的回答", scope).block();

var context = new MemoryContext(memory, scope, 5);
context.writeBack("请简短一点", "好的。").block();
```

`userId`、`agentId`、`sessionId` 由应用选择，不由模型生成。Memory 不是框架的短期会话存储。支持记忆增删改查、搜索、列会话与会话消息；请求自动使用当前 Workspace。

`addMemories` / `addMessages` 可通过第三个参数传入 `Map<String, String>` metadata。搜索可使用 `MemoryStore.SearchOptions(topK, metadata, enableRerank, minSimilarity, minScore)`，不需要的选项传 `null`，由服务端使用默认值。会话消息分页使用 `listMemorySessionMessages(sessionId, scope, maxResults, nextToken)`，将响应中的 nextToken 传入下一次调用，不自动拉取全部历史。

直接调用失败时，`AgentCoreException` 提供 `operation()`、`status()`、`serviceCode()`、`requestId()` 和原始 cause（若有）。明确的业务拒绝不属于写入结果未知；只有 AddMemories 的传输结果不明或服务端 5xx 被标记为 `AddMemoriesOutcomeUnknownException`，不能自动重复写入。

应用按已验证的用户身份选择 Scope，可将跨会话检索与本会话写入分开：

```java
var context = new MemoryContext(memory,
    new MemoryScope("user-123", "my-agent", null),        // 读取该用户跨会话记忆
    new MemoryScope("user-123", "my-agent", "session-456"), // 写入本会话
    5);
```

| 框架 | 检索与自动回写入口 |
| --- | --- |
| Spring AI | 注册 `new MemoryAdvisor(context, true)`，作用于整个工具循环外层 |
| LangChain4j | 创建 `new MemoryAdapter(context, true)`；将 `retriever()` 注册到 AiServices，并用 `call(input, supplier)` 或 `stream(input, supplier)` 执行完整 Agent |
| AgentScope Java | 注册 `new MemoryMiddleware(context, true)`，使用原生 Middleware 生命周期，覆盖 `call()` 和 `streamEvents()` |

自动回写默认关闭，开启时要求显式写 Scope；只需要检索时不传 `true`，写 Scope 可为 `null`。开启后只写本轮用户输入和最终文本答案，不写过程说明、工具结果或历史对话，也不在失败、取消或框架报告非正常结束时补写。LangChain4j 同步入口使用 `Result<String>`，以保留完成原因；不要手动重复调用 `writeBack()`。独立的 `MemoryRetriever` 和显式 `MemoryContext.writeBack()` 仍可使用。

每个请求使用对应身份的 `MemoryContext`，不要将不同用户的 Scope 保存在共享可变对象中。检索和自动回写的服务端失败会记录警告，不阻断 Agent、不自动重试；配置与编程错误仍然抛出。直接调用 Memory API 时仍返回错误。不确定的写入结果不能盲目重试。完成判断依赖框架提供的结束状态，不从回答正文推测。

完整示例见 [MemoryAdapters.java](examples/src/main/java/example/MemoryAdapters.java)。

Memory 失败日志包含操作名、记忆空间、状态码、服务端错误码、RequestId 和经过脱敏、截断的错误说明；服务端未返回的字段可能为空。框架自动记忆降级时也会记录诊断信息，直接调用则通过 `AgentCoreException` 保留相同字段。日志不打印请求正文或完整响应。SDK 使用 SLF4J，应用需要提供 Logback 等日志实现并开启 `io.agentcore` 的 WARN 级别；SDK 不强制安装日志实现。

模型、Controller 和 Task Service 的 HTTP 错误也会记录状态码、上游 RequestId、错误码和脱敏后的错误说明；模型流式调用的 HTTP 拒绝同样支持诊断。凭证获取错误记录 `GetResourceAPIKey` 及 Provider 名，不记录凭证值。协作配置沿用旧版本时，`teams.update_ignored` 日志包含失败原因。工具结果仍使用简洁的错误分类，服务端诊断说明不注入给模型。

RequestId 优先来自响应正文，正常返回的响应也支持从 `x-acs-request-id` 响应头读取。当前 Tea 依赖在 HTTP 错误时不保留响应头，因此仅在错误响应头中提供 RequestId 的情况无法读取，日志不会自行生成替代 ID。

## 执行事件与服务协议

三种框架均提供执行事件转换入口：

- `SpringAIEvents.stream(model, prompt)`：使用 Spring AI 原生工具循环，转换文本、工具调用和结果。
- `LangChain4jEvents.from(() -> assistant.chat(input))`：转换 AiServices 的 `TokenStream`。
- `AgentScopeEvents.from(agent.streamEvents(input))`：转换 AgentScope 原生执行事件。

每次执行创建独立转换状态，保留调用 ID、事件顺序及过程/最终消息边界。不要仅提取模型文本再传给服务层，否则工具结果和消息边界会丢失。

`AgentCoreServer` 提供 `/healthz`、`/readyz`、`/agui`、`/openai/v1/chat/completions`，两个 SSE 接口均提供 15 秒空闲心跳。`/healthz` 检查进程存活；`/readyz` 默认就绪，也可传入应用就绪状态函数，未就绪时返回 503。应用负责 Agent 实例与会话历史管理，Server 不自动给所有请求共用一份对话历史。

AG-UI 请求 Body 必须提供非空字符串 `threadId` 和 `runId`，缺失或无效时返回 HTTP 400，不执行 Agent。`X-AgentCore-Session-ID` 仅作为请求 Header 保留，不覆盖协议 ID。OpenAI Chat Completions 不要求这两个字段。

`InvokeHandler` 是 Agent 执行入口，`ProtocolHandler` 负责注册协议路由并调用该入口，两者分开。默认启用 AG-UI 和 OpenAI；构造时传入协议列表则使用该列表，传空列表可关闭内置协议。扩展时复制 `AgentCoreServer.defaultProtocols()` 再加入自定义协议，见 [ServerExtensions.java](examples/src/main/java/example/ServerExtensions.java)。

AG-UI 可表达多条消息及中间工具结果。标准 OpenAI Chat Completions 只能表达一个 assistant 响应中的文本和 tool_calls，不能无损表达 Agent 内部多轮消息边界或中间工具执行结果；需要完整过程展示时使用 AG-UI。Server 不提供 Responses API 服务入口。

## 测试与当前边界

`mvn verify` 包含单元测试和本地真实 HTTP／SSE 协议测试，不需要云端凭证：

| 验证层次 | 覆盖内容 |
| --- | --- |
| 单元测试 | 配置解析与延迟挂载、失败恢复、Header 合并和保护、公共/私网地址选择、Skill 路径安全 |
| 本地协议测试 | Model、Responses 流、Ollama 原生 Chat/流式/Embedding、STS/WAT、bootstrap JWT/SA 续期和配置下载、MCP 三种传输 |
| 框架执行测试 | 三框架的过程文本→同轮多个工具→纯工具调用轮→最终回答；调用 ID、每轮消息边界及 AG-UI/OpenAI 输出；Memory 自动回写和读写 Scope、失败与取消不回写、并发隔离 |
| Server 与协作测试 | AG-UI/OpenAI 输出及心跳、自定义协议、就绪状态；任务上下文、服务错误、文件上传/下载、Team 目录分页同步和内置 Skills |
| 云端 E2E | 尚未完成，正式发布前需使用目标环境资源与凭证联调 |

本地测试运行真实框架和协议库，但服务端为测试服务，不代表已验证云端网关、RAM 权限或私网连通性。协作是按需安装的独立模块，使用方式见 [协作模块](collaboration/README.md)。此版本不包含 Matrix 收消息组件或 AgentRun 兼容层。

SDK 使用 SLF4J，不绑定日志实现。应用自行选择 Logback 等实现。不要开启会输出凭证、原始请求 Header 或敏感模型内容的第三方调试日志。

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
