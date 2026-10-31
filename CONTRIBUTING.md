# Contributing to Droidance

Droidance is a general-purpose Android mobile-use agent. Read the [language policy](docs/LANGUAGE_POLICY.md) and [security policy](SECURITY.md) before making changes.

## Development

Install JDK 17 and Android SDK 35, configure `local.properties`, then use the Gradle wrapper. Configure provider keys in the app. Never commit credentials, device exports, signing keys or local machine configuration.

The runtime has one model decision loop. Keep business strategies, reusable skills and completion policies in Markdown configuration. Kotlin owns orchestration, protocol validation, device execution and durable facts. [docs/RULES.md](docs/RULES.md) explains which rule files reach the model in every round, how to add a model reasoning capability and which configuration groups are meant to be tuned. Respect the reviewed module dependency graph and keep every source package owned by one module.

## Changes and validation

Describe the concrete problem, resulting behavior and relevant validation in each pull request. Add behavioral tests for changed contracts. Keep unrelated changes separate. Choose local checks for the affected behavior and report any checks not run.

- `scripts/verify.sh`: architecture, resource/schema/native checks, unit tests, lint and debug APK.
- `scripts/verify.sh release`: the same checks plus the R8-processed release APK.
- `scripts/verify.sh platform`: the same checks plus connected device tests; use a dedicated test device and report the tested environment.

Report code checks, device behavior and real model-driven business outcomes separately. A build or a tool's successful return does not establish task success.

Database version 1 is the public baseline. Every subsequent schema change must increment the version, retain exported schemas, register a non-destructive migration and include a MigrationTestHelper test with representative data. Do not use destructive fallback.

## Community

Use the issue templates for reproducible bugs and focused proposals. Report security vulnerabilities privately according to SECURITY.md. Follow the [Code of Conduct](CODE_OF_CONDUCT.md).
