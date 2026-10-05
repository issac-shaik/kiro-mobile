# Notifications

The default APK posts connection and permission notifications using a visible Android foreground service. Allow notifications and keep Tailscale connected. Battery restrictions, Doze and force-stop can interrupt delivery.

## Optional Firebase push

No Firebase project or credentials are included. To enable push while the connection is suspended:

1. Create a Firebase Android app with package `dev.kiromobile.app` (or your custom `kiroApplicationId`) and Cloud Messaging enabled.
2. Add `firebaseAppId`, `firebaseApiKey`, `firebaseProjectId` and `firebaseSenderId` to your private `~/.gradle/gradle.properties`, using your Firebase app's values.
3. Build and install your APK.
4. Give a least-privilege service account permission to send FCM messages. Keep its JSON outside the repository; set `KIRO_FIREBASE_SERVICE_ACCOUNT` to its absolute path before starting the companion.
5. Reopen the paired app to register the device. Reopen after companion restarts; registrations are memory-only.

Push contains opaque request/session IDs, never chat text, commands or pairing keys. Android delivery restrictions still apply, and push cannot wake an offline PC. Approvals are reviewed inside the conversation.
