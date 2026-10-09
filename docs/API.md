# Companion API v1

Session, command and push endpoints require `Authorization: Bearer <pairing key>`. QR pairing uses certificate-pinned HTTPS on the PC's private Tailscale address. Browser origins and redirects are not supported. Errors return `{ "error": "human-readable reason" }` with a non-2xx status. This is a single-owner API, not a hosted multi-tenant account service.

- `GET /pair` serves the QR page only on PC loopback, with a local Host header and no proxy/browser-origin headers. Its versioned invitation includes `kind:kiro-mobile-pair`, `version:1`, `network`, `endpoints`, `certSha256`, `code` and `expiresAt`; it never contains the durable key.
- `POST /v1/pair` is available only on the private HTTPS listener. It accepts `{ "code": "one-time invitation" }` without a bearer key and returns `{ "token": "pairing key" }`. Invitations expire after five minutes and can be redeemed once. Clients must validate the QR's mandatory PC certificate fingerprint before sending the code and pin that certificate for later requests.

- `GET /v1/state?after=<revision>` returns the normalized session snapshot. If unchanged, holds the response for up to 20 seconds. `revision` is scoped to the companion process; reconnect from revision zero after restarting. Clients retain their draft locally and never replay prompts automatically.
- `POST /v1/command` accepts a UUID `requestId`, `type`, and type-specific fields. Successful commands return `{ "ok": true }`. `prompt` acknowledges accepted work; later completion/failure arrives in the state stream. Retrying an identical command with the same ID is deduplicated for the last 500 commands; reusing an ID with different data is rejected. This cache is not durable across companion restarts.
- `POST /v1/push/register` accepts `{ "deviceId": "stable random id", "token": "FCM device token" }` and returns `{ "configured": boolean }`. The companion stores at most ten registrations in memory. Re-register after restart.

Commands:

| type | fields | behavior |
| --- | --- | --- |
| refresh | none | Refresh sessions and account credits |
| create | cwd | Start an Autopilot session within an allowed workspace |
| load | sessionId, handoffConfirmed:true | Restore a saved session after explicit desktop handoff |
| select | kind:model\|reasoning\|agent\|autopilot, value | Select an advertised config value, agent preset, or Autopilot on/off |
| prompt | text, attachments:[{name,mimeType,data}] | Send text and base64 image blocks through ACP |
| cancel | none | Request turn cancellation; cancel pending permissions |
| permission | permissionId, optionId or null | Reply with a real option ID, or cancel the request |

The snapshot includes session descriptors, selected session, bounded transcript, pending permission choices, live model/reasoning catalogs, agent preset/native status, account usage, busy state and push-provider availability. It never includes Kiro account credentials or a push device token. Internal permission `rpcId` is an implementation detail; clients use opaque `id` only.

Autopilot defaults on and is acknowledged by Kiro before sending. Select `kind: "autopilot"` with `value: "on"` or `"off"` while idle. Snapshots expose `autopilot`, `autopilotSupported`, and nullable `contextUsagePercent`. Transcript entries with role `summary` carry `summary.creditsUsed` and `summary.elapsedMs` from Kiro turn-completion telemetry; missing values remain null.

Transcript activity: `role: "thinking"` carries streamed Markdown in `text`. `role: "tool"` carries a stable `tool:<toolCallId>` ID and a `tool` object with `title`, `kind`, `status`, and optional `input`, `output`, `content`, `locations`, `failureReason`, and `waitingForPermission`. Partial tool updates retain omitted fields; supplied content replaces the previous content. Replay is marked inactive, and unfinished calls become interrupted when a turn or connection ends. Transcript text and tool details share a bounded history.

Protocol references: [Kiro V3 session updates](https://kiro.dev/docs/cli/v3/acp-migration/#4-handle-session-updates), [ACP tool calls](https://agentclientprotocol.com/protocol/v1/tool-calls).
