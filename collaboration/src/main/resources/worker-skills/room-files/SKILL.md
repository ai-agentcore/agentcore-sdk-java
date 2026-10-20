---
name: room-files
description: Download chat attachments and deliver local files or images to the current Matrix room.
---

# Room files

Use this Skill when the user asks you to inspect a chat attachment or deliver a file in this chat.

- Take the exact attachment event ID from the current message or its background. Never guess IDs.
- Call `agentteams_download_room_file(event_id)` to download it. A successful download means only that the file exists locally. Use the application's existing text, document or image reading tools to inspect it.
- If the application has no suitable reading tool or the model cannot understand images, explain that limitation. Do not infer file contents from the filename.
- To deliver a requested artifact, call `agentteams_send_room_file(local_path, caption)` with a file inside the collaboration workspace. The SDK selects the current room; you do not supply room IDs, media URLs or credentials.
- Only report that a file was sent after the tool returns `status: sent`. A local path or a Markdown link to that path is not delivery. Do not blindly retry a send whose result is unknown.
- Send only the requested deliverables. Never send credentials, configuration or unrelated workspace files.
- You do not need Team membership or a team-context query for room attachments. Task files and Team shared files remain separate: use `task-execution` and `file-sharing` for those workflows. Sending a room attachment does not submit a Task Result.
