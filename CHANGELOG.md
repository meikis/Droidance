# Changelog

All notable user-visible changes to Droidance are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and public releases use [Semantic Versioning](https://semver.org/). Version selection, entry rules, and the release process are defined in the [versioning and update policy](docs/release/VERSIONING_POLICY.md).

Record a notable change in `Unreleased` within the same pull request that implements it. Use only the categories that contain changes: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and `Security`. Mark incompatible entries with `**Breaking:**`, name the affected contract, and give migration steps. At release time, move the entries under the exact version and its release date.

## [Unreleased]

## [0.1.0] - 2026-10-31

### Fixed

- Conversation deletion physically removes selected history after cancellation settles and reclaims unreferenced attachments while preserving retained evidence. Filesystem cleanup failures are reported instead of being shown as success.
- Public update comparison follows SemVer, including numeric prerelease identifiers and preview-to-stable upgrades; downloaded APKs must have a greater version code and support the device.

### Added

- New releases are announced with a dialog or notification and a download arrow beside the version in the drawer; the package opens the system installer after download.
- A manual release-draft workflow validates an existing tag, increasing public version codes, signed APK identity, certificate, alignment and checksums before preparing a draft for device acceptance and manual publication.

### Changed

- Preset model providers start without a service endpoint; users enter their own endpoint before enabling them.

- Refer to skills directly in conversation using `@`; select matching skills inline and open their details from the reference.

- The home top bar uses a lighter two-line sidebar toggle that matches the new-conversation action's size and weight, and the app name is one step smaller. The drawer header's close and search controls follow the same size, and the drawer search field matches the nav chips' shape, fill and type and focuses when opened.
- The light theme uses dimmer, softer layering for OLED comfort: a lightly warm grey canvas, cards and floating layers each one small step lighter with no pure white anywhere, a navigation drawer in the page's canvas grey with a lighter scrim, and text fields filled with a faint tonal wash that outline only on focus.

- Provider settings can keep a fixed reasoning effort or let the same Agent suggest the next decision's effort. Runtime feedback separates execution and observation facts from model-assessed outcomes, with sent-effort and fallback metrics for device evaluation.
- The single-agent turn loop now separates model requests, context checkpoints, tool execution, completion checks and recovery behind a TurnHost boundary. Core packages identify their owning module.
- English is the default UI language, with complete Simplified Chinese resources.
- Built-in skills ship in APK assets and remain read-only. Existing user-owned packages with the same name are preserved.
- Fork updates require an explicitly configured distribution repository. Default builds disable update checks.

### Security

- Restricted cleartext transport to loopback endpoints. Remote and LAN provider endpoints require HTTPS.
- Device builds and CI verify libadb provenance, native binary hashes and license assets. CI also validates the R8 release APK.

[Unreleased]: https://github.com/YbbLab/Droidance/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/YbbLab/Droidance/releases/tag/v0.1.0
