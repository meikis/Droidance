# Contributing to Droidance

Droidance is a general-purpose Android mobile-use agent. Read the [security policy](SECURITY.md) before making changes. Keep user-facing strings in localized Android resources (English defaults and complete Simplified Chinese translations); write Agent rules and reusable Skills in English.

## Development

Install JDK 17 and Android SDK 35, configure `local.properties`, then use the Gradle wrapper. Configure provider keys in the app. Never commit credentials, device exports, signing keys or local machine configuration.

The runtime has one model decision loop. Keep business strategies, reusable skills and completion policies in Markdown configuration. Kotlin owns orchestration, protocol validation, device execution and durable facts. Respect the reviewed module dependency graph and keep every source package owned by one module.

## Changes and validation

Describe the concrete problem, resulting behavior and relevant validation in each pull request. Verify changed contracts and report how they were checked. Keep unrelated changes separate. Choose local checks for the affected behavior and report any checks not run.

- `scripts/verify.sh`: architecture, resource/schema/native checks, lint and debug APK. Local tests run only when their sources are present.
- `scripts/verify.sh release`: the same checks plus the R8-processed release APK.
- `scripts/verify.sh platform`: requires locally retained device tests; use a dedicated test device and report the tested environment.

Documentation under `docs/`, test sources, test fixtures and debug acceptance probes are kept locally and are not distributed in this repository. Public CI checks the published source and build inputs; it does not claim to run the local test suite.

Report code checks, device behavior and real model-driven business outcomes separately. A build or a tool's successful return does not establish task success.

Database version 1 is the public baseline. Every subsequent schema change must increment the version, retain exported schemas, register a non-destructive migration and include a MigrationTestHelper test with representative data. Do not use destructive fallback.

## Community

Use the issue templates for reproducible bugs and focused proposals. Report security vulnerabilities privately according to SECURITY.md. Follow the [Code of Conduct](CODE_OF_CONDUCT.md).
