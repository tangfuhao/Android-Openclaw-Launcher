# OpenClaw Gateway Protocol v3 - Reference Schemas

Official TypeBox schema files from the OpenClaw repository, used as the
Single Source of Truth for the Android client's protocol data classes.

## Source

- Repository: https://github.com/openclaw/openclaw
- Path: `src/gateway/protocol/` and `src/gateway/protocol/schema/`
- Branch: `main`
- Downloaded: 2026-03-02
- Protocol version: **3** (defined in `schema/protocol-schemas.ts`)

## Files

| File | Contents |
|------|----------|
| `schema/frames.ts` | Frame types, ConnectParams, HelloOk, Tick, Shutdown, Error |
| `schema/sessions.ts` | Session CRUD params (`key` field, not `sessionKey`) |
| `schema/logs-chat.ts` | ChatSend, ChatHistory, ChatAbort, ChatInject, ChatEvent |
| `schema/exec-approvals.ts` | Approval request (`id`, `command`) and resolve (`id`, `decision`) |
| `schema/agent.ts` | Agent/Send/Poll event schemas |
| `schema/snapshot.ts` | Presence, HealthSnapshot, StateVersion |
| `schema/protocol-schemas.ts` | Master schema registry, PROTOCOL_VERSION constant |
| `schema/primitives.ts` | NonEmptyString, shared primitive types |
| `client-info.ts` | GATEWAY_CLIENT_IDS, GATEWAY_CLIENT_MODES, GATEWAY_CLIENT_CAPS |
| `connect-error-details.ts` | ConnectErrorDetailCodes enum |

## How to Update

```bash
BASE=https://raw.githubusercontent.com/openclaw/openclaw/main/src/gateway/protocol
cd reference/openclaw-protocol
for f in schema/frames.ts schema/sessions.ts schema/logs-chat.ts \
         schema/exec-approvals.ts schema/agent.ts schema/snapshot.ts \
         schema/protocol-schemas.ts schema/primitives.ts \
         client-info.ts connect-error-details.ts; do
  curl -fsSL "$BASE/$f" -o "$f"
done
```

## Key Field Mappings (Android Kotlin ↔ Official Schema)

These are the critical differences that caused protocol mismatches:

| Kotlin Property | Wire JSON Field | Schema Source |
|----------------|----------------|---------------|
| `SessionsResetParams.sessionKey` | `"key"` | sessions.ts |
| `SessionsDeleteParams.sessionKey` | `"key"` | sessions.ts |
| `SessionsCompactParams.sessionKey` | `"key"` | sessions.ts |
| `SessionsPatchParams.sessionKey` | `"key"` | sessions.ts |
| `ApprovalResolveParams.id` | `"id"` | exec-approvals.ts |
| `ApprovalResolveParams.decision` | `"decision"` | exec-approvals.ts |
| `ApprovalRequestPayload.id` | `"id"` | exec-approvals.ts |
| `ApprovalRequestPayload.command` | `"command"` | exec-approvals.ts |
