# Changelog

All notable user-visible changes to Droidance are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and public releases use [Semantic Versioning](https://semver.org/). Version selection, entry rules, and the release process are defined in the [versioning and update policy](docs/release/VERSIONING_POLICY.md).

Record a notable change in `Unreleased` within the same pull request that implements it. Use only the categories that contain changes: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and `Security`. Mark incompatible entries with `**Breaking:**`, name the affected contract, and give migration steps. At release time, move the entries under the exact version and its release date.

## [Unreleased]

### Fixed

- Screen capture, viewer mirroring and task takeover on Android 11–14: the sidecar now uses each release's platform APIs and reads the display stream where on-demand capture is unavailable.

## [0.1.0] - 2026-10-31

First public release.

[Unreleased]: https://github.com/YbbLab/Droidance/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/YbbLab/Droidance/releases/tag/v0.1.0
