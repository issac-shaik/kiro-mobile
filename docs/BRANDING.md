# Visual references

The mobile interface follows the installed Kiro IDE 1.1.70 **Kiro Dark** theme: `#211d25` editor background, `#28242e` input background, `#4a464f` input borders, `#b080ff` accent and `#7138cc` primary buttons. The chat composer follows the IDE's bordered input with agent/model selectors, with larger touch targets for Android.

The original Kiro ghost, wordmark and application icon were sourced from the user's installed Kiro distribution:

- `out/vs/workbench/contrib/welcomeDialog/common/media/kiro.svg`
- `out/vs/workbench/contrib/welcomeDialog/common/media/kiroWordmark.svg`
- `resources/win32/code_150x150.png`

The SVG paths are preserved as Android vector resources; the launcher PNG is copied unchanged. These Kiro brand assets belong to their respective owners and are excluded from this repository's MIT license. The remaining interface and toolbar glyph implementation is original code.

This is an independent mobile companion, not an official Kiro application. Forks can replace the branding in `core/design` without changing session or connection modules.

Reference: https://kiro.dev/docs/ide/chat/
