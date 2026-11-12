# AgentCore Memory plugin

可选 Maven 模块，为 Java harness 提供显式来源事件采集、原生检索结果和有预算的参考上下文。核心 API 见[原生 Context Memory](../docs/context-memory.md)。需要 JDK 17+、Maven 3.9+。

制品 `io.github.ai-agentcore:agentcore-memory-plugin:0.1.0` 尚未发布。本分支依赖核心 `agentcore-sdk:0.1.6`，因为已发布的 `0.1.5` 不包含原生 Context API。这里只准备版本，不发布制品。

```sh
mvn -pl memory-plugin -am -Dmaven.test.skip=true package
export AGENTCORE_MEMORY_PLUGIN_ENABLED=true
export AGENTCORE_MEMORY_STORE_NAMES="mymemory:true,yourmemory:false"
```

```java
import io.agentcore.memory.plugin.MemoryPlugin;
import io.agentcore.memory.plugin.MemoryPlugin.Binding;

var nativeMemory = sdk.contextMemory(storeName);
var plugin = MemoryPlugin.fromNative(nativeMemory);
var session = plugin.session(
    new Binding(storeName, trustedUserIdOrNull, trustedAgentId, trustedTeamIdOrNull),
    nativeSessionId);
var recalled = session.recall(query).join();
// context() 包含服务实际返回的 type/source，作为不可信参考数据提供给业务框架。
// nativeResult() 保留完整命中、分数、scope、版本、来源和 requestId。
var receipts = session.capture(persistedRecords).join();
// receipt.sourceId() 关联宿主记录，eventId() 是服务存储标识。
// receipt.nativeReceipt() 与 requestId() 保留服务回执。
```

方法返回 CompletableFuture。异步服务使用 thenCompose 串接，无需阻塞请求线程。插件不关闭注入的公共核心客户端或其凭据提供器；核心客户端必须绑定与 Binding 相同的库名。用户身份可为 null，不生成占位用户。SDK 只校验实际请求的 scope，跨类型及无 UID 的查询策略仍由 harness 决定。

`fromNative(client)` 读取并固定 System.getenv()，`fromNative(client, trustedEnvironmentMap)` 使用显式可信映射。全局开关默认 false，库名列表默认空。关闭或未列出的库不发送请求；`name:true` 开启采集和召回，`name:false` 只关闭采集，保留召回。布尔值只接受 true/false，忽略大小写与两侧空白；重复库名、空条目、缺失布尔值或格式非法均报错。配置变化后需重建插件。开关不授予资源权限。

SourceRecord 含 sourceId 与 SourceEvent(type, timestamp, data)。五种来源是 message、tool_call、tool_result、model_request、model_response。原生路径使用核心 `ContextEvent.fromSource` 转换，不把 records、host 或 sourceId 发送给云端。timestamp 和完整内容由业务 producer 持久保存。每批 1 至 100 条，单事件不超过 900000 UTF-8 字节，总请求不超过 1 MiB。sourceId 仅用于当前提交与回执的关联，SDK 不生成来源 ID、去重或持久排队。

原生 STORED 回执映射为 CaptureState.STORED，明确 FAILED/REJECTED 映射为 FAILED，其他合法服务状态映射为 UNKNOWN，均由 nativeReceipt 保留原始状态。STORED 不代表提取完成或已能检索。传输或回执不确定会抛出 `MemoryPluginException`，其 outcomeUnknown() 为 true，requestId() 保留已知 ID，cause 保留核心异常。错误不会转换成成功空结果或持久 unknown 状态。宿主负责通过核心 ListEvents 对账，SDK 不自动重试。

RecallOptions 默认 SHARED，支持 SESSION；默认最多三条，context 上限 3600 个 Unicode 码点，含不可信参考标记与 JSON 开销。可用四参数构造函数传入核心 MemoryType 列表。nativeResult 保留服务返回的完整命中，context 与 memories 是按预算选择的便利视图。缺失 type/source 不造默认值。空查询或关闭召回返回空便利结果，未调用服务时 nativeResult 为 null。上下文生成不证明模型使用。

插件默认期限 1500 毫秒，覆盖凭据获取与请求。取消 Future 会取消响应式订阅，但不能证明远端写入未受理；取消已发出采集前由宿主保存未知状态，取消后不保证回调异常。插件没有第二份 outbox，不负责 hooks、提示词编排或提取后的二次写入。

已有网关调用方继续使用 `new MemoryPlugin(gatewayEndpoint, tokenSupplier, captureEnabled)` 或 `fromEnvironment(gatewayEndpoint, tokenSupplier)`。网关 endpoint 仍为 `/v2/memory`，每次请求读取 Bearer token；显式构造器保持默认关闭采集、开启召回，且不读取两个环境开关。原生入口不要求网关 token，也不会回退到网关。旧网关 QUEUED/STORED/UNKNOWN/FAILED 状态和调用签名保留。

完整接线见 [MemoryPluginExample.java](examples/MemoryPluginExample.java)。目前仅做构建与静态检查，未做真实云端端到端验收。
