# Companion API v1

All endpoints require `Authorization: Bearer <pairing key>`. Use private HTTPS. Browser origins and redirects are not supported. Errors return `{ "error": "human-readable reason" }` with a non-2xx status. This is a single-owner API, not a hosted multi-tenant account service.

- `GET /v1/state?after=<revision>` returns the normalized session snapshot. If unchanged, holds the response for up to 20 seconds. `revision` is scoped to the companion process; reconnect from revision zero after restarting. Clients retain their draft locally and never replay prompts automatically.
- `POST /v1/command` accepts a UUID `requestId`, `type`, and type-specific fields. Successful commands return `{ "ok": true }`. `prompt` acknowledges accepted work; later completion/failure arrives in the state stream. Retrying an identical command with the same ID is deduplicated for the last 500 commands; reusing an ID with different data is rejected. This cache is not durable across companion restarts.
- `POST /v1/push/register` accepts `{ "deviceId": "stable random id", "token": "FCM device token" }` and returns `{ "configured": boolean }`. The companion stores at most ten registrations in memory. Re-register after restart.

Commands:

| type | fields | behavior |
| --- | --- | --- |
| refresh | none | Refresh sessions and account credits |
| create | cwd | Start a supervised session within an allowed workspace |
| load | sessionId, handoffConfirmed:true | Restore a saved session after explicit desktop handoff |
| select | kind:model\|reasoning\|agent, value | Select an advertised config value or supported agent preset |
| prompt | text, attachments:[{name,mimeType,data}] | Send text and base64 image blocks through ACP |
| cancel | none | Request turn cancellation; cancel pending permissions |
| permission | permissionId, optionId or null | Reply with a real option ID, or cancel the request |

The snapshot includes session descriptors, selected session, bounded transcript, pending permission choices, live model/reasoning catalogs, agent preset/native status, account usage, busy state and push-provider availability. It never includes Kiro account credentials or a push device token. Internal permission `rpcId` is an implementation detail; clients use opaque `id` only.
