# 原生 Context Memory

`AgentCore.contextMemory` 绑定一个已有 Workspace 中的 Memory Store，提供 CreateEvents、分页 ListEvents 和 SearchMemories。原有 `memory(name)`、MemoryStore CRUD 与 Map 返回值保持兼容。此能力随核心 `io.github.ai-agentcore:agentcore-sdk:0.1.6` 准备发布。

```java
import io.agentcore.AgentCore;
import io.agentcore.memory.ContextEvent;
import io.agentcore.memory.MemoryScope;
import io.agentcore.memory.ContextMemoryModels.*;
import java.util.List;

var memory = sdk.contextMemory(storeName);
var scope = new MemoryScope(null, trustedTeamId, trustedAgentId, nativeSessionId);
var search = new SearchOptions(scope,
    List.of(MemoryType.SKILL, MemoryType.SESSION_SUMMARY), 20, null, null, null, null);
var recalled = memory.searchMemories(query, search, CallOptions.defaults());
// 在响应式流程中组合 recalled；hit.memory() 保留类型、来源、scope 和 provenance。

var event = ContextEvent.message(timestamp, role, contentBlocks, callId);
var created = memory.createEvents(scope, List.of(event), CallOptions.defaults());
// 每次订阅都会执行一次写入。对 created 只订阅一次并持久保存真实回执。

var page = memory.listEvents(new ListEventsOptions(scope, nextToken, 100), CallOptions.defaults());
// page.items().get(i).envelope() 原样保留服务事件行；使用 page.nextToken() 翻页。
```

四维 `userId`、`teamId`、`agentId`、`sessionId` 独立可选，缺失使用 null；空串、`*` 和 `__default__` 无效。原三参数 `new MemoryScope(userId, agentId, sessionId)` 继续可用。客户端只检查请求实际过滤的 scope 维度；没有过滤 teamId 时，返回带 teamId 的记录仍合法。scope 是业务分区，访问授权由服务和凭据决定。

`SearchOptions.types` 仅支持 semantic、skill、user_profile、session_summary，null 或空列表表示全部，不支持 auto。显式 topK 为 1 至 200；null 不传该字段，使用服务默认值。结果保留 `score`、`similarity`、`memory.type/source/valid/scope/sourceEventIds/version/createdAt/updatedAt`。缺失的可选字段保留 null，成功空列表与错误分别返回和抛出。`ListEventsOptions.maxResults` 为 1 至 100；SDK 不自动遍历分页，也不假设事件行有嵌套 event 字段。

客户端复用已有 CredentialProvider，每次请求使用固定 purpose `context`。`sdk.contextMemory(name, endpoint)` 的显式 URI 优先于 `AGENTCORE_MEMORY_ENDPOINT`，之后使用 `https://agentcore.{region}.aliyuncs.com`。运行时入口复用已有环境投影，不使用控制面 URL。显式 Workspace/AK 入口不读取运行时配置文件。已有 ControlPlane 和 MemoryStore 继续使用 `highcode_sdk`。

独立调用方可注入已有公共凭据提供器，不需要网关 token 或私有运行时对象。

```java
import io.agentcore.memory.ContextMemoryClient;

var memory = ContextMemoryClient.fromCredentials(new ContextMemoryClient.Options(
    workspaceId, regionId, storeName, endpoint, credentialProvider));
// 已有显式 AK 可由 purpose -> Mono.just(accessKeyCredential) 提供。
```

`ContextEvent.fromSource(type, timestamp, data)` 将 message、tool_call、tool_result、model_request、model_response 转为部署支持的 message envelope。message 中的 opaque 值转换为按对象键排序的紧凑 JSON 字符串，format 追加 `;encoding=json`。其他来源包装成 `harness.<kind>;encoding=json` 的 opaque 内容，tool_result 使用 role=tool 并保留 callId。`ContextEvent.typed` 是服务支持直接 typed 事件时的显式选择，SDK 不在失败后切换格式或重发。时间戳为非负安全整数毫秒，事件和请求有大小限制。

CreateEvents 返回完整、唯一且范围合法的 inputIndex 回执。只有 `StoredEvent` 及其非空 eventId 表示 STORED，其他状态由 `OtherEventReceipt` 原样保留。sourceId 仍由宿主维护，与云 eventId 不同。SDK 不维护队列、去重账本、跨库查询策略或自动重试。

服务拒绝、验证错误和凭据失败抛出结构化异常，保留已知 requestId。写入发出后遇到超时、连接丢失、5xx 或不完整回执会抛出 `CreateEventsOutcomeUnknownException`，其 `outcomeUnknown()` 为 true。调用方应持久记录未知状态，并按自身策略使用 ListEvents 对账后决定下一步，不应盲目重写。

`CallOptions.timeout` 包含运行时解析、凭据获取和请求执行，默认 30 秒。Reactor disposal 保持取消语义，取消后不会向下游补发异常；生成式 SDK 的阻塞调用也不能保证远端停止。宿主必须在取消已发出的写入前把本地状态记为未知，不能依赖取消后的 onError。这里未声明真实云端端到端验收。
