# Release notes

## 0.1.2 — 2026-10-19

- 明文 HTTP 请求使用 HTTP/1.1，避免默认 h2c 升级被 AgentCore Task Service 网关拒绝并返回 HTTP 426，恢复原生协作中的任务查询、接单、文件上传和结果提交。HTTPS 保持原有协议协商。
- 新增协议回归测试，覆盖 JSON、SSE、multipart 上传及文件下载。

## 0.1.1 — 2026-10-18

- Memory 写入默认改为异步提取，`addMemories()` 和 `addMessages()` 返回值的 `data.taskId` 为任务 ID。原先依赖 `data.memories` 的调用需使用带 `sync` 参数的重载并传入 `true`。
- 新增 `getMemoryTask(taskId)`，用于查询提取进度、记忆 ID 和任务错误。
- Spring AI、LangChain4j 和 AgentScope 的记忆回写改为等待任务受理，不等待提取完成。回写开关、消息范围和错误处理方式保持不变。
