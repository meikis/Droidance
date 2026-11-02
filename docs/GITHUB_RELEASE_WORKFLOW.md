# Release preparation and fork updates

## Repository setup before publication

Enable GitHub private vulnerability reporting and verify that maintainers can receive and respond to reports before making the repository public. Configure the `release` environment and its signing secrets only in GitHub; keep the signing key in secure storage. Verify the final repository URL, release signing identity and maintainer contact separately. Re-resolve the release dependency graph, update [the dependency inventory](RELEASE_DEPENDENCIES.md), and review the final distribution's notices and artwork rights. Do not publish a draft until CI, final signed-APK checks and the intended device acceptance have been completed.

## Automated validation and draft creation

[CI](../.github/workflows/ci.yml) validates source, native provenance and module ownership, runs unit tests and lint, and builds the R8-processed unsigned release APK. Python release-tool tests run in CI and `scripts/verify.sh`.

[Prepare release draft](../.github/workflows/release.yml) is a manual workflow against an **existing** public tag. It checks out that tag's exact commit, runs `scripts/verify.sh release`, signs the APK, verifies the final artifact, and creates a complete GitHub Release **draft**. It does not publish a release or run device acceptance. Tags are not created by the workflow. Existing drafts and releases are rejected instead of replacing their assets.

## Prepare a public version

1. Set `versionName` and `versionCode` in `app/build.gradle.kts`. Public names use `MAJOR.MINOR.PATCH`, optionally followed by `-beta.N` or `-rc.N`. Every public APK of the same package needs a greater version code, including transitions between preview and stable.
2. Move the intended `CHANGELOG.md` entries from `Unreleased` into a nonempty `## [VERSION] - YYYY-MM-DD` section, and add the same section to `CHANGELOG.zh.md` in Simplified Chinese. The GitHub Release description uses only the English entry. `release.json` carries the separate English and Chinese entries in `release_notes.en` and `release_notes.zh`; clients from 0.1.4 select exactly one using the current app language. Existing clients read only the public Release body, so their notes for the upgrade to 0.1.4 are English.
3. Complete applicable local checks, commit the release inputs, and create and push `vVERSION` for that commit. Do not move an existing public tag.
4. Run **Prepare release draft** with that tag. `scripts/release.py identity` rejects mismatched tags, dirty sources, and a version code that does not exceed every other reserved SemVer tag in the fetched repository. Keep public tags available; the code check depends on their history.
5. Download the draft's final signed APK and complete clean-install, supported upgrade, affected device-behavior, and real-model task acceptance as applicable. Record the exact artifact digest and each unperformed check. Publish the draft manually only after acceptance.

`release.json` records the public version, version code, package, minimum Android API, source commit, preview status, APK size, final SHA-256, signing certificate SHA-256, and localized update notes. The APK also has a `.apk.sha256` companion. Before a draft is created, the verifier checks the actual APK manifest against the tagged configuration, verifies alignment and signature, and checks the certificate against the configured distribution identity. Native hashes and required notices are checked again after signing.

If a run fails before a draft exists, retry against the same tag and source identity. A draft left by a failed asset upload needs manual inspection and removal before retry; the workflow will not overwrite it. A published correction requires a new public version and version code.

## Signing configuration

Configure the GitHub environment named `release` with the following secrets:

- `SIGNING_KEYSTORE_BASE64`: Base64-encoded release keystore.
- `SIGNING_ALIAS`: signing key alias.
- `SIGNING_STORE_PASSWORD`: keystore password.
- `SIGNING_KEY_PASSWORD`: private-key password.

Set the environment variable `SIGNING_CERT_SHA256` to the expected certificate fingerprint. Restrict the environment to trusted release maintainers and refs. Signing credentials stay outside the repository, passwords are passed through environment variables, and the temporary keystore is removed after the signing step. Keep a secure offline backup of the signing identity; existing installations depend on its continuity.

The workflow uses SDK build-tools `35.0.0`, NDK `27.0.12077973`, JDK 17, and the repository's checksummed Gradle wrapper. Linux workflow execution and production signing require validation in the configured repository; local script tests are not evidence of a production release.

## Fork distributions and updates

Update checking is disabled unless the builder explicitly sets `-PdroidanceUpdateRepository=owner/repository`. The draft workflow configures this value to its own repository. Configure the fork's repository and signing identity together. Default local builds make no upstream update request.

The updater compares public SemVer tags with `BuildConfig.VERSION_NAME`. It checks when the app opens and from the background job, at most every six hours; a new stable release shows a dialog in the foreground or a notification in the background, and the drawer keeps a download arrow beside the version until the update is installed. GitHub's `releases/latest` selects stable releases, so beta and release-candidate APKs are installed manually for now. Preview-channel selection remains future work. A preview installation can recognize a newer stable release of the same base version.

The updater requires the selected GitHub asset's SHA-256 digest, validates its size, and checks the APK package, current signing identity, minimum Android API, increasing version code, and newer public version before installation. A checksum mirror is only a transport fallback; it cannot select the expected digest. Missing digests prevent download.

## Public database baseline

The public AgentEventDatabase starts at version 1 as a clean-install baseline.

Every later schema change must retain prior exported schemas, provide a registered migration, and exercise MigrationTestHelper with representative data. Destructive fallback is prohibited.
