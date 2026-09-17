---
name: file-sharing
description: Share non-task files in the current Team shared space.
---

# Team file sharing

Use `agentteams_filesync_list` and `agentteams_filesync_stat` to discover files under `shared/`. Use `agentteams_filesync_pull` to download a file or directory into your configured collaboration workspace, and `agentteams_filesync_push` to upload a workspace file or directory.

The SDK selects the Team from the request context. Never invent a Team ID or use a local path outside the workspace. Stage artifacts created elsewhere into the workspace before uploading them. Pull overwrites existing destination files unless `overwrite` is false; it does not delete unrelated local files. Push does not delete remote files.

Task and Subtask inputs and deliverables belong to the task file tools, not Team shared space. Do not upload credentials, private configuration or signed URLs. Only report successful transfers after the tool returns success.
