# Validation record

Validated on Windows with Kiro CLI 2.25.0 and an Android API 36.1 emulator. The packaged APK targets Android 8.0 and newer; other physical devices have not been tested.

## Passed

- Thirteen Node tests: authentication, request ordering and deduplication, long polling, permission option validation, cancellation, workspace boundaries, bounded history, credits normalization, media mapping and push-provider routing.
- Android debug APK and instrumentation APK builds, Android lint, and APK signature verification.
- Instrumented Android flow: pairing, Android Keystore storage, session selection, native agent/reasoning controls, chat submission, permission notification posting while the app is backgrounded, rejection, and notification removal. This test uses a clearly labeled deterministic demo companion. App-view captures isolate unrelated emulator System UI dialogs.
- Real Kiro ACP: session discovery, account credits retrieval, advertised model/reasoning/mode configuration, supervised mode, permission rejection without writing the requested file, saved-session restoration, and streamed assistant text.

## Limits of this validation

- The Android flow and real Kiro protocol checks were run separately. A physical phone connected over Tailscale has not been tested here.
- Firebase delivery needs the fork owner's project and service-account configuration. The provider integration is implemented and unit tested; end-to-end cloud push was not tested without those credentials. The default APK uses connected background notifications.
- Joining an arbitrary currently running IDE/CLI harness and simultaneous desktop/mobile synchronization remain unverified. The supported default path is saved-session restoration with an explicit desktop handoff.
- Image attachment mapping is tested at the protocol boundary; model-specific image understanding is not claimed.

The default APK uses Android's debug signing identity. Use your own maintained release signing key when distributing a fork.

## Version 0.2 interface update

- Original Kiro launcher PNG verified byte-for-byte against the installed IDE asset; ghost and wordmark SVG paths preserved as Android vectors.
- Kiro Dark colors and branding isolated in the new `core/design` module.
- Debug APK and test APK built; lint reports zero errors (five existing compatibility/dependency warnings); APK signature verifies.
- Updated instrumented flow passed on a clean Android API 36 emulator, including agent, model and reasoning selection, sending a message, background permission notification posting and rejection.
- Onboarding, conversation, agent chooser and permission-card captures visually reviewed. Captures use demo data and are in `dist/screenshots`.
