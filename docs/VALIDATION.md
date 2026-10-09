# Validation record

## Version 0.3.4

- 27 companion tests passed, covering thinking chunk order, tool updates by ID, partial field updates, failed/interrupted calls, permissions, session isolation, replay, and bounded tool output.
- Android build and lint passed. Both emulator flows passed, including streamed Markdown thinking, expanding tool input/output, failure status, and keeping a row collapsed through later updates.
- Real Kiro V3 emitted file-read tool calls and results, which replayed on resume. A reasoning task using an advertised Claude Sonnet 4.6 model emitted 425 thinking chunks into one transcript entry. The Auto model did not emit thinking for the earlier fixture prompts; visibility depends on the events Kiro provides.
- Reviewed activity and streaming-thinking screenshots. No physical phone validation was performed here.

## Version 0.3.3

- Kiro CLI 2.27.1 confirmed native Autopilot on/off, context percentage, and per-turn credit usage and elapsed milliseconds with a short response that used no tools.
- Android build and lint passed; emulator checks cover the remaining-credit label, default Autopilot, context popup, Markdown, turn summary, permission notifications, and certificate-pinned QR pairing.
- Companion tests cover failed configuration acknowledgment, busy-state restrictions, session isolation, missing telemetry, summary deduplication, and demo behavior in both Autopilot modes.

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
