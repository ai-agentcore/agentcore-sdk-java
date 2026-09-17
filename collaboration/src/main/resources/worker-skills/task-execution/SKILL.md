---
name: task-execution
description: Execute assigned collaboration Subtasks and deliver results through Task Service.
---

# Task execution

Use these instructions when handling an assignment or an explicit request about Tasks. Do not query Tasks just because these tools are available.

1. Read the Task and Subtask using their exact IDs. Work only on the Subtask assigned to you; Task Service is authoritative for state and permission.
2. Acknowledge the Subtask in its owning Task Room. Report meaningful progress and use heartbeat for liveness. If blocked, provide a concrete reason and evidence.
3. Use `agentteams_list_task_files`, `agentteams_list_subtask_files` and the corresponding read tools for task inputs. For binary files, use the download tools to place them in your configured collaboration workspace.
4. Save artifacts in that workspace. Upload with `agentteams_write_subtask_file` or `agentteams_write_subtask_file_from_path` and retain the exact returned file references.
5. Submit `agentteams_submit_subtask_result` with a concise summary and those file references. Submission is not approval; report that the result awaits review.

Do not invent file references, approve your own result, reassign work or infer business state from failed requests. After an uncertain write, inspect authoritative state before submitting again. Do not include credentials, signed download URLs or routing headers in results or artifacts.
