# Kiro Mobile

Android client for Kiro running on your PC. Pair by QR code, resume saved conversations, and review tool approvals from your phone.

**[Download APK v0.3.4](https://github.com/issac-shaik/kiro-mobile/raw/refs/heads/main/dist/kiro-mobile-debug.apk)** · [SHA-256 checksum](dist/SHA256SUMS.txt)

## Setup

### 1. Install the phone apps

- Install the APK on Android 8.0 or newer. Allow notifications and camera access when prompted.
- Install [Tailscale](https://tailscale.com/download) on your phone and PC. Connect both to the same tailnet.

### 2. Prepare the PC

Install Node.js 22+ and [Kiro CLI with V3 ACP](https://kiro.dev/docs/cli/v3/acp-migration/), then:

```powershell
kiro-cli login
git clone https://github.com/issac-shaik/kiro-mobile.git
cd kiro-mobile
npm ci
```

### 3. Choose your project folders

Only this repository is allowed by default. To open other projects, create `bridge/config.local.json`:

```json
{
  "workspaces": ["C:\\Projects"]
}
```

Use your own absolute folder paths. To allow the entire C: drive, use `"C:\\"`. This local configuration is never committed.

### 4. Start the companion

```powershell
npm run pair
```

Keep the terminal open. On the PC, open **[http://127.0.0.1:8787/pair](http://127.0.0.1:8787/pair)**.

### 5. Scan and connect

1. Keep Tailscale connected on your phone.
2. Open Kiro Mobile and tap **Scan PC QR code**.
3. Scan the QR code on the PC screen.

QR codes expire after five minutes and work once; refresh the page if needed. Existing installs can scan from **Settings → Scan PC QR code**. Pairing survives companion restarts.

### 6. Open a conversation

- **Resume:** select a saved session, stop any active desktop turn, and confirm the handoff.
- **New chat:** enter the full PC project-folder path, such as `C:\Projects\my-app`. It must be inside an allowed workspace root.

Keep the PC awake, signed in to Kiro, and running the companion. Wi-Fi and mobile data both work through Tailscale.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Cannot connect | Check Tailscale on both devices, tailnet access rules, and the PC firewall. |
| Firewall blocks pairing | Allow TCP **8788** only on the PC's Tailscale IP, from tailnet addresses (`100.64.0.0/10`). |
| Workspace outside allowed roots | Add the project folder to `bridge/config.local.json`, then restart the companion. |
| QR expired or already used | Refresh the PC pairing page and scan again. |
| PC identity changed | Scan again after changing the PC's TLS identity, pairing key, or Tailscale IP. |
| Notifications missing | Allow notifications and check battery restrictions. Default alerts require an active connection. |

## Security and limitations

- Phone-to-PC traffic uses **certificate-pinned HTTPS over Tailscale**. HTTPS ends on the PC; Tailscale relays cannot decrypt the traffic. No public relay, Serve, or Funnel is required. [Encryption details](https://tailscale.com/docs/concepts/tailscale-encryption).
- Kiro login stays on the PC. Pairing keys use Android Keystore encryption. Keep `bridge/.local/` private. To revoke pairing: stop the companion, remove `pairing.json`, restart, and scan again.
- Autopilot starts **on**. Turn it off in the composer to review tool approvals on your phone. Allowed folders restrict session selection; they are not an OS sandbox.
- The companion restores saved sessions in its own Kiro process. Simultaneous IDE/phone control is unsupported; one conversation is controlled at a time.
- Shows credits remaining, context usage, and credits used/time after each turn. Streams Markdown answers, expandable thinking, and tool calls with status and details. Supports model/reasoning controls, cancellation, and four image attachments of up to 8 MB each. Audio/video transcription is unsupported.
- Thinking appears when Kiro emits it; restored sessions show the activity Kiro replays. Long tool results are shortened on mobile.
- The APK uses a debug signing key. Use your own release key for production distribution.

## Build and test

Configure the Android SDK through `ANDROID_HOME` or ignored `local.properties`:

```powershell
npm ci
npm test
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

For integration checks, attach an API 29+ emulator:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
node scripts/android-smoke.mjs
```

Other modes: `npm run pair:wifi` (local Wi-Fi), `npm run demo` (demo data), `npm run bridge` (legacy loopback-only mode).

[Optional Firebase notifications](docs/NOTIFICATIONS.md) · [Branding and attribution](docs/BRANDING.md)
