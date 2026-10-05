# Kiro Mobile

A lightweight native Android/Kotlin client for a private Kiro companion running on your PC. Kiro executes tools and owns its login on the PC; the phone never receives your Kiro account credentials.

## Install and connect

1. Install `dist/kiro-mobile-debug.apk` on Android 8.0 or newer. Allow notifications when prompted.
2. Install Node.js 22+ and a Kiro CLI build with the V3 ACP engine on your PC. Run `kiro-cli login` as your normal desktop user. No elevated Windows administrator account is required.
3. From the repository folder, run `npm run bridge`. It creates an ignored `bridge/.local/pairing.json` containing your random pairing key. Keep that file private.
4. Install Tailscale on your PC and phone. Sign both into your private tailnet. Run `tailscale serve --bg http://127.0.0.1:8787` on the PC. **Use Serve, not Funnel.** Copy the HTTPS address it prints.
5. In Kiro Mobile, enter that HTTPS address and the `token` value from `bridge/.local/pairing.json`. Keep Tailscale connected on the phone.
6. Select a session, confirm the desktop handoff, or create a new chat using an absolute workspace path on the PC.

By default, the companion allows only workspaces inside the repository folder. To allow other projects, configure a JSON array of absolute roots before starting it:

```powershell
$env:KIRO_WORKSPACES = '["C:\\Personal"]'
npm run bridge
```

For macOS/Linux, use `KIRO_WORKSPACES='["/home/me/projects"]' npm run bridge`.

## Current capabilities and limitations

- Session discovery, saved conversation replay, streaming text, cancellation, and permission review through V3 ACP.
- Default, Spec, Quick Spec, Bug Fix and Plan use Kiro's advertised native modes. A clearly labeled prompt-guidance fallback supports engines missing those modes.
- Model and reasoning choices come from Kiro's live configuration, including asynchronous updates. Models without effort support show no selectable reasoning levels.
- Account credits come from `_kiro/account/getUsage`. The adapter isolates this compatibility probe because current Kiro releases implement the method without advertising it. Unavailable usage is displayed as unavailable, never as zero.
- JPEG, PNG, WebP and GIF attachments: at most four images, each at most 8 MB. Audio/video transcription is outside this version.
- One selected conversation per companion; switching is blocked while a turn or permission is pending. Multiple readers can connect, but control is shared, not per-user.
- **Existing live IDE/CLI takeover is not established.** The default adapter restores saved sessions in its own harness. Finish/stop the desktop turn and use a single controller before continuing on mobile. It does not intercept IDE traffic or guarantee simultaneous desktop synchronization. Workspace-local agents/MCP configuration may differ from client-supplied IDE settings. The app never writes Kiro session files directly.
- All sessions opened through this companion are switched to **supervised** (`autopilot=off`) so approval requests reach the phone. Existing durable Kiro permissions still apply.
- The PC must remain awake, Kiro signed in, and the companion running. A sleeping/offline PC shows a reconnecting state; messages are not silently queued for later execution.

## Notifications

The installable default APK runs a visible Android remote-messaging foreground service and posts permission notifications from the private connection. Tapping an alert opens the app; approvals are always reviewed in the conversation. Android notification permission, Tailscale connectivity, manufacturer battery management, Doze, and force-stop can affect connected delivery.

For genuine push while the connection is suspended, fork owners can configure **Firebase Cloud Messaging**. No Firebase project, Google credentials or account-specific IDs are included in this repository or its default APK.

1. Create your own Firebase Android app using package `dev.kiromobile.app` (or your changed application ID), with Cloud Messaging enabled.
2. Add these Gradle properties to your private `~/.gradle/gradle.properties` or pass them via `-P`: `firebaseAppId`, `firebaseApiKey`, `firebaseProjectId`, `firebaseSenderId`. Values come from your Firebase app configuration; do not commit your configuration.
3. Build your APK. The app initializes Firebase only when all four properties are supplied.
4. Give a least-privilege service account permission to send FCM messages for that project. Store its JSON outside the repository and set `KIRO_FIREBASE_SERVICE_ACCOUNT` to its absolute path before starting the companion.
5. Reopen the paired app to register its FCM device token. Reopen after a companion restart as device registrations are currently memory-only.

Push payloads contain only opaque request/session IDs; no chat text, tool commands, or pairing key. The screen reports whether **both** the app and PC provider are configured. A high-priority FCM alert is still subject to Android delivery restrictions and does not wake an offline PC.

## Modular architecture

| Module | Responsibility |
| --- | --- |
| `core/design` | Shared Kiro Dark palette, toolbar glyphs and original Kiro brand resources |
| `core/protocol` | Typed mobile state, attachment and command parsing |
| `core/connection` | `SessionConnection` interface, HTTPS client, encrypted pairing store, session stream |
| `feature/chat` | Native screens, session browser, controls, composer, permission review |
| `feature/notifications` | Foreground connection alerts, deduplication, optional Firebase provider |
| `app` | App identity, resources, manifest and provider configuration |
| `bridge/src/transport.mjs` | JSON-RPC framing over stdio or an explicitly configured ACP WebSocket |
| `bridge/src/kiro.mjs` | Kiro discovery, session lifecycle, configuration and media mapping |
| `bridge/src/push.mjs` | Optional push provider; replace `send(token, permission)` to add another provider |
| `bridge/src/server.mjs` | Authenticated versioned mobile API, ordering and duplicate-command protection |

Fork owners use their own local Kiro login and random pairing key. No subscription, Kiro token, personal path, or Firebase project is embedded in source.

Set Gradle property `kiroApplicationId` to give a fork its own Android package identity. The Firebase Android registration must use that same ID.

`KIRO_ACP_URL` optionally selects a compatible existing ACP WebSocket endpoint instead of starting a new harness. This is an integration hook, **not verified support for joining arbitrary running IDE sessions**. Plain WebSockets are accepted only for loopback. Keep this endpoint local and expose only the authenticated companion through Serve.

## Build and test

```powershell
# Configure Android SDK using ANDROID_HOME or ignored local.properties.
.\gradlew.bat :app:assembleDebug :app:lintDebug
npm test
node scripts/probe-kiro.mjs --usage
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the developer's standard Android debug key. For distribution, use your own release signing key; never commit it. Changing signing keys requires uninstalling an existing debug install or preserving your own signing identity. Release builds block cleartext networking and screenshot capture; debug builds additionally allow loopback/emulator HTTP for tests and screenshots for QA.

`npm run demo` starts the deterministic demo adapter, labeled **Demo**, with no Kiro account usage. The default Android app does not silently substitute demo data when a real connection fails.

After building the Android test APK with `:app:assembleDebugAndroidTest`, run `node scripts/android-smoke.mjs` with an API 29+ emulator attached. It starts a loopback-only demo server, installs the two test packages, and exercises real pairing encryption, session controls, chat, background permission notification posting and rejection. App-view captures are saved to `dist/screenshots`; process-scoped view inspection isolates unrelated system UI dialogs. Use `node scripts/live-smoke.mjs --permission` and `node scripts/live-resume.mjs --stream` for optional real Kiro checks; these use a dedicated ignored test workspace and a small amount of account credits. Test writes are denied.

Run `python scripts/package.py` to package the APK, source ZIP and SHA-256 checksums in `dist/`. Local credentials, SDK paths, caches and build output are excluded from the source archive.

## Security defaults

- Companion binds to loopback only; no public listener, port forwarding, account credential copying, auto-approval or arbitrary RPC forwarding.
- Restrict Tailscale grants to your phone and the companion's HTTPS port. Tailscale's initial allow-all tailnet policy should be narrowed. Use MFA on the identity provider and revoke a lost phone.
- Pairing credentials use Android Keystore AES-GCM; app backup is disabled. Requests carry the key in an authorization header, never a URL. Redirects and browser origins are rejected.
- Workspace access checks resolve real paths to reject traversal/sibling-prefix/symlink escapes. Permission options must match the actual pending request and can be answered only once.
- Allowed workspace roots restrict session selection; they are not an operating-system sandbox for Kiro's tools. Tool access follows Kiro's own permission policy and any existing durable approvals.
- Media sizes, request rate, command IDs and transcript history are bounded. Kiro stderr and secrets are not streamed to the phone.
- To revoke a pairing key: stop the companion, remove/replace `bridge/.local/pairing.json`, restart, and pair again. Keep the private service-account file and pairing file readable only by your OS account.

Sources: [Kiro ACP migration](https://kiro.dev/docs/cli/v3/acp-migration/), [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve), [Android foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types), [Firebase Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started).

## Interface and branding

Version 0.2 follows Kiro Dark with the original ghost launcher icon and wordmark, compact chat toolbar, bordered composer, and inline agent/model/reasoning selectors. It adapts the IDE layout to Android touch targets. Brand resources are isolated in `core/design`; see [visual references and attribution](docs/BRANDING.md).
