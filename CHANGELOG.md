# Changelog

All notable user-visible changes to Droidance are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and public releases use [Semantic Versioning](https://semver.org/). Version selection, entry rules, and the release process are defined in the [versioning and update policy](docs/release/VERSIONING_POLICY.md).

Record a notable change in `Unreleased` within the same pull request that implements it. Use only the categories that contain changes: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and `Security`. Mark incompatible entries with `**Breaking:**`, name the affected contract, and give migration steps. At release time, move the entries under the exact version and its release date.

## [Unreleased]

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

[Unreleased]: https://github.com/YbbLab/Droidance/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/YbbLab/Droidance/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/YbbLab/Droidance/releases/tag/v0.1.0
