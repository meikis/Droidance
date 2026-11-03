# Changelog

All notable user-visible changes to Droidance are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and public releases use [Semantic Versioning](https://semver.org/).

Record a notable change in `Unreleased` within the same pull request that implements it. Use only the categories that contain changes: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and `Security`. Mark incompatible entries with `**Breaking:**`, name the affected contract, and give migration steps. At release time, move the entries under the exact version and its release date. Add the same version to [CHANGELOG.zh.md](CHANGELOG.zh.md), which supplies the Chinese update notes.

## [Unreleased]

### Added

- Add a Back button to the virtual-space control island during manual control and teaching.

### Fixed

- Keep automatic device reconnection quiet unless a connection problem was shown to the user.
- Improve system Back focus handoff while preserving native keyboard input, and bind each manual Back to its original virtual session.

## [0.1.5] - 2026-11-02

### Added

- Show chat and conversation token usage; hide counts when unavailable.

### Fixed

- Recover temporary screenshot failures and pause for blocked input permissions.
- Explain task failures more clearly and retain diagnostic references.

### Changed

- Keep documentation, tests and acceptance tools local.
- **Custom rule overrides:** replace `observation_unavailable_diagnostics` with `recovery_handoffs` and refresh bundled notice keys.

## [0.1.4] - 2026-11-02

### Added

- Optional ADB persistence reconnects through a saved local port after network changes. It is disabled by default and requires USB debugging to be enabled manually.

### Fixed

- Sidecar recovery now tries the saved local ADB port without waiting for Wi-Fi.
- Port setup waits until tasks are idle. Pairing details are retained if port shutdown cannot be confirmed.
- Tasks without a configured model now explain how to add a model and API key.
- ADB shell output is decoded correctly for process detection and recovery diagnostics.

### Changed

- GitHub release descriptions are English. In-app update notes use the selected app language, with separate English and Chinese text.

## [0.1.3] - 2026-11-02

### Added

- Every provider can now use Chat Completions or Responses, and the edit page shows the final request URL.

### Changed

- The provider list and chat model list show the default provider first, then custom providers, then presets.
- A 403 from a model service now asks you to check API key permissions or plan, and shows the service's reason with sensitive values removed.
- A task stopped by a rejected API key now says to check the key instead of showing a generic execution error.
- After pairing, device control keeps working without Wi-Fi and on mobile data. Pairing and reconnecting after a phone restart still need Wi-Fi.
- The background control service restarts itself if it is killed or crashes, without Wi-Fi. Resetting pairing stops it, and it does not restart once developer options are off.
- Update notes in the app follow the app language.

### Fixed

- A complete `/responses` address no longer becomes `/responses/responses`.
- After Wi-Fi drops, pairing settings keep showing Connected while the background control service is running, and reading notifications no longer needs the live wireless debugging connection.
- A restarted app can reconnect to the background control service without Wi-Fi while the screen is off.
- The virtual display now recovers when you lock or unlock the phone during a task.

## [0.1.1] - 2026-11-01

### Added

- Built-in cloud model provider presets. Enter your own API key and model ID to enable a provider, or start from a custom endpoint.

### Changed

- Android 11–13 support is now marked experimental. Low-memory and Android Go devices report that they are unsupported.

### Fixed

- Screen capture, viewer mirroring and takeover failures on Android 11–14, including "screen channel unavailable" on Samsung devices.
- Key and text input on Android 11–13, and foreground detection on vendor system formats.
- A clear message when the system blocks input, with the setting to change.
- Virtual displays created on a foldable's cover screen are rebuilt after unfolding.
- Text input no longer follows focus into another app or system window.

## [0.1.0] - 2026-10-31

First public release.

[Unreleased]: https://github.com/YbbLab/Droidance/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/YbbLab/Droidance/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/YbbLab/Droidance/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/YbbLab/Droidance/compare/v0.1.1...v0.1.3
[0.1.1]: https://github.com/YbbLab/Droidance/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/YbbLab/Droidance/releases/tag/v0.1.0
