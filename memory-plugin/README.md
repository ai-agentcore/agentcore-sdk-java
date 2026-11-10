# AgentCore Memory plugin（首稿）

可选 Maven 模块，通过单独部署的 Memory 接入服务为 Java Agent harness 提供显式采集和召回。不改变现有 MemoryStore，也不依赖 harness 仓库路径。需要 JDK 17+ 和 Maven 3.9+。

```sh
mvn -pl memory-plugin -am -Dmaven.test.skip=true package
```

制品坐标 `io.github.ai-agentcore:agentcore-memory-plugin:0.1.0`，当前仅源码首稿，尚未发布。

```java
import io.agentcore.memory.plugin.MemoryPlugin;
import io.agentcore.memory.plugin.MemoryPlugin.Binding;

var plugin = new MemoryPlugin(gatewayEndpoint, () -> gatewayToken, true);
var session = plugin.session(
    new Binding(identity.memoryStoreName(), identity.userId(), identity.agentId()),
    identity.sessionId());

var recalled = session.recall(query).join();
// 将 recalled.context() 作为参考数据交给业务框架。
var receipts = session.capture(persistedRecords).join();
```

identity 来自可信业务入口，persistedRecords 来自已保存的真实执行记录。方法返回 CompletableFuture，示例用 join 展示顺序；异步服务使用 thenCompose 串接，不必阻塞请求线程。异步错误通过 CompletionException 的 cause 获取 MemoryPluginException。

完整函数示例见 `examples/MemoryPluginExample.java`。核心类不持有可变的当前用户或会话，plugin 可以跨请求复用。

## 使用环境变量配置

```sh
export AGENTCORE_MEMORY_PLUGIN_ENABLED=true
export AGENTCORE_MEMORY_STORE_NAMES="mymemory:true,yourmemory:false,memstore-1:true"
```

```java
var plugin = MemoryPlugin.fromEnvironment(gatewayEndpoint, () -> gatewayToken);
// plugin.session(new Binding("mymemory", userId, agentId), sessionId)
```

全局开关默认 false，关闭时不发送采集或召回请求。库名列表默认为空，未列出的库不启用；`name:true` 开启采集和召回，`name:false` 只关闭采集，保留召回。布尔值只接受 true/false，忽略大小写及条目两侧空白；重复库名、空条目、缺失布尔值或格式非法均报错。

fromEnvironment 调用时读取并固定配置，修改后需重建插件。默认使用 System.getenv()，也可在第三参数传可信环境 Map。环境入口按逐库策略执行，不接受显式采集或召回参数覆盖。显式 new MemoryPlugin(...) 仍只使用构造参数，不读取这两个变量；基础 MemoryStore API 不受影响。配置库名不授予访问权限，每个 binding 仍需对应的服务令牌。

## 契约与范围

采集开关通过构造参数显式开启，召回默认开启，默认超时 1500 毫秒。完整构造函数可配置 recallEnabled 和 100 至 10000 毫秒的 Duration。每次 HTTP 请求从 Supplier 获取网关 Bearer token，它与云端 AK、模型 API key 分别管理，必须匹配精确 binding。

SourceRecord 包含 sourceId 和 SourceEvent(type, timestamp, data)。五种事件为 message、tool_call、tool_result、model_request、model_response；timestamp 为非负整数毫秒，data 保存原始结构化内容。来源 ID 由业务 producer 命名，并与 timestamp、完整内容一起持久保存，重试使用同一份记录。每批 1 至 100 条，单事件不超过 900000 UTF-8 字节，总请求不超过 1 MiB。

CaptureReceipt 的 QUEUED、STORED、UNKNOWN、FAILED 保留网关状态。QUEUED 仅代表已受理，STORED 不代表提取完成或已能检索。采集传输或响应不确定时 MemoryPluginException.outcomeUnknown() 为 true，不自动重试。SDK 没有本地持久队列；业务负责保存尚未确认的事件，网关负责受理后的持久投递。超时或取消不能证明服务端未受理。

RecallOptions 的默认范围为 SHARED（同一授权范围跨会话），也支持 SESSION。默认最多三条，context 上限 3600 个 Unicode 码点，包含不可信参考标记及 JSON 格式开销；结果保留 sourceEventIds。空查询或关闭召回返回空结果，服务错误抛出。准备 context 不证明模型使用。

首稿不自动安装框架 middleware、不维护 checkpoint、不从流式展示文本推断工具完成事件。前置召回、完整事件采集和上下文注入由业务 harness 接线，避免同时对同一事件启用原 Memory 自动回写。

接入服务需支持 `host=sdk` 的 `/v2/memory/capture` 与 `/search`。首稿未完成真实云端端到端验收。
