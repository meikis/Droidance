# Droidance Versioning and Update Policy

Droidance uses semantic versions for public releases, and an increasing Android version code for installation order. This policy covers version selection, release naming, application updates, and repository change logs.

The rules below describe the target release process. Implementation gaps are recorded at the end; this document does not itself change the updater or CI.

## Version identities

| Identity | Example | Purpose and rule |
| --- | --- | --- |
| `versionName` | `0.2.0-beta.1` or `0.2.0` | Public version; select it when preparing a public release. |
| `versionCode` | `41`, `42`, `43` | Android installation order; allocate a greater integer for each new public APK of the same package. |
| Git commit SHA | Full commit hash | Exact source identity; include it in release metadata and diagnostic records. |
| APK SHA-256 | Hash of the final signed APK | Exact artifact identity; calculate it after signing. |

Keep the authoritative version values in [app/build.gradle.kts](../../app/build.gradle.kts). Generate release metadata from those values and verify it against the built APK. Do not maintain independent version strings in release titles, scripts, or update notes.

Stable and preview APKs sharing a package name share one increasing `versionCode` sequence. Never reset it at a new date, semantic version, or channel. Development and repository-test packages remain separate from the public package. Android uses `versionCode` to prevent installation of a lower version code; the public name serves a different purpose. See [Android app versioning](https://developer.android.com/studio/publish/versioning).

Reserve the release identity in source before building. A workflow retry retains that identity; it must not overwrite an already published APK with different bytes. A corrected artifact receives a new public version and a new version code.

Ordinary changes do not each become public releases. Contributors should describe each change and its validation in the pull request.

## Selecting a public version

Use `MAJOR.MINOR.PATCH`, with optional `beta.N` or `rc.N` prerelease identifiers. The compatibility promise covers documented external protocols, Skill formats, configuration and import/export formats, automation definitions, and supported Android versions.

| Change | Version choice |
| --- | --- |
| Compatible defect correction | Increase the patch version, such as `0.2.0` to `0.2.1`. |
| New capabilities or substantial compatible changes to documented behavior | Increase the minor version, such as `0.2.1` to `0.3.0`. |
| Incompatible change to a declared contract after `1.0` | Increase the major version, such as `1.4.0` to `2.0.0`. |
| Breaking change during `0.x` development | Increase the minor version and explicitly document migration requirements. |

Internal refactoring and database schema increments do not automatically change the major version. Judge the effect on existing users and integrations. Prompt, rule, and bundled Skill changes are release changes when they affect behavior; choose the increment by their effect, rather than their file extension.

Remain on `0.x` while the compatibility promise is evolving. Use preview releases for public trials. Release `1.0.0` when core workflows, data-preserving upgrades, and documented contracts have an explicit support commitment.

Version precedence follows [Semantic Versioning](https://semver.org/): `0.2.0-beta.1 < 0.2.0-beta.2 < 0.2.0-rc.1 < 0.2.0`. Build metadata does not affect SemVer precedence. Public release names omit build metadata.

## Release names

| Surface | Example |
| --- | --- |
| APK `versionName` | `0.2.0-beta.1` |
| Git tag | `v0.2.0-beta.1` |
| GitHub Release title | `Droidance 0.2.0-beta.1` |
| APK filename | `droidance-0.2.0-beta.1.apk` |
| Checksum filename | `droidance-0.2.0-beta.1.apk.sha256` |

Public release tags use `v` followed by the exact APK `versionName`. Date-only tags are not public release tags. The public version is the only user-facing version identity.

Use the preview channel for `beta` and `rc` releases, and the stable channel for releases without a prerelease identifier. `rc` is optional. A title or marketing subtitle never determines update order or channel selection.

## Application updates

Stable is the default subscription. Preview requires an explicit user choice and includes stable releases as well as beta and release candidates. Development builds are distributed through CI artifacts or manual installation.

Select eligible releases by channel and Android support, then offer an update only when its declared `versionCode` exceeds the installed code. Validate the public version syntax and its consistency with the tag. Read structured release metadata from the official source; do not infer installation order from titles, release prose, or date suffixes. After download, verify the APK version, package, signing identity, size, and SHA-256 against the expected release.

Keep automatic checks at application startup with at least 24 hours between successful checks, and retain a manual check action. A failed check remains a failure; it does not establish that the installation is current. Show release changes and migration requirements before the user starts installation. Wait for the active Agent task to settle before installing an update.

Changing from preview to stable does not authorize a downgrade. If the available stable APK has a lower version code, wait for a newer eligible stable release. Do not recommend uninstalling the application as the ordinary update path.

GitHub's [`/releases/latest`](https://docs.github.com/en/rest/releases/releases#get-the-latest-release) returns a full release and excludes drafts and prereleases. Preview support therefore needs explicit release selection; marking a release as prerelease alone does not add preview updates to the current client.

Retain the existing official-source digest, package, and signing checks. Preserve and securely back up the public signing key. Do not replace it casually: existing installations depend on signing continuity. See [Android app signing](https://developer.android.com/studio/publish/app-signing).

## Repository change logs

Maintain one English `CHANGELOG.md` at the repository root as the source for public release changes. Start with `Unreleased`, followed by dated releases in descending order. At release time, translate each version into `CHANGELOG.zh.md` for the Chinese update notes; the translation has no `Unreleased` section. The examples here are not release history.

Follow [Keep a Changelog](https://keepachangelog.com/en/2.0.0/). Use only the categories that contain changes:

| Category | Content |
| --- | --- |
| `Added` | New capabilities. |
| `Changed` | Changes to existing behavior. |
| `Deprecated` | Supported behavior scheduled for removal. |
| `Removed` | Removed capabilities or contracts. |
| `Fixed` | Corrections to incorrect behavior. |
| `Security` | Changes addressing vulnerabilities. |

Record notable changes with the implementation. Explain the trigger and resulting user-visible behavior, and link the related issue or pull request when available. Consolidate repeated development fixes into one final entry. Routine internal cleanup and tests usually do not need public entries.

Mark incompatible entries with `**Breaking:**`, identify the affected contract, and provide migration instructions or a migration-guide link. Describe dependency changes by their effect on users. Keep known issues and detailed verification results in the affected release notes or issue tracker.

At release time, move the applicable `Unreleased` entries into the exact dated version. Generate the GitHub Release changes from that entry, then append artifact information, upgrade requirements, validation coverage, known issues, and contributor acknowledgements. Automatically generated commit lists may supplement the curated notes.

Record beta and release candidates under their exact public versions. Consolidate changes from the preceding stable version when preparing stable release notes, so stable subscribers can understand the full upgrade. Preserve the earlier preview records and link each released version to its tag or comparison.

Begin with the first release under this process.

## Release preparation and validation

1. Curate the version's changes and migration requirements; select its public version, channel, and next version code.
2. Commit the release inputs and bind the tag to that exact commit. A manual dispatch must check out the specified existing tag.
3. Verify the tagged source using the applicable repository checks, including release shrinking, lint, and packaging checks. Publishing must depend on successful checks for that commit.
4. Build and sign the final APK. Assert that its public version equals the tag without `v`, its version code matches the release configuration, and its signing identity is correct.
5. Verify clean installation using the final signed APK. When a prior public build exists, also exercise a supported upgrade, check retained settings, history, Skills, and memory, and verify affected device behavior.
6. Generate release metadata and checksum files from the final artifact. Include the version name, version code, channel, package, minimum Android version, commit SHA, build identifier, APK size, digest, and signing certificate identity.
7. Upload the complete assets and notes to a draft, then publish with the correct prerelease status. Preserve published tags and artifact bytes; prefer immutable GitHub Releases when available.

The current packaging commands and signing secrets are described in [GitHub release workflow](../GITHUB_RELEASE_WORKFLOW.md). GitHub recommends preparing a draft and attaching all assets before publishing an [immutable release](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).

Release notes distinguish code and test checks, device behavior, and real model-driven task outcomes. Record device, Android version, connection mode, provider/model, sample scope, and limitations for task success or performance claims. A build, tool receipt, or Agent `done` call alone does not prove the user's business goal was completed. Unperformed checks remain explicitly unverified.

## Implementation status

The following implementation snapshot is dated 2026-10-23. Local checks and device/business acceptance are separate; a workflow definition does not establish that production signing or publication has succeeded.

| Area | Current state | Remaining work |
| --- | --- | --- |
| Version configuration | Gradle is the authority for public version and version code. Release preparation rejects a reused or lower code than other reserved public tags. | Allocate the first public identity and preserve public tag history. |
| Release identity | Manual draft workflow checks out an existing exact tag, validates tag/version identity and a clean source tree, then runs shared release checks. | Execute the workflow in the target GitHub repository. |
| Signing and drafts | Final manifest values, alignment, signature, pinned certificate and native inputs are verified; checksum and metadata accompany a draft. Existing releases/drafts are never overwritten. | Configure signing credentials, validate the signed APK on devices, and manually publish accepted drafts. |
| Update selection | Default local builds disable updates. Configured distributions request the latest stable release and compare against the public version alone. | Explicit preview-channel subscription and client-side structured metadata selection remain future work. |
| Version comparison | Strict SemVer precedence handles numeric preview identifiers, preview-to-stable transitions, build metadata, and invalid inputs without a date/string fallback. | Keep behavioral precedence regressions with future changes. |
| Download verification | Official asset digest, size, package and signing identity checks remain; the APK must also support the device and have a greater version code and public version. | Exercise signed upgrade and data-retention acceptance before publication. |
| Change logs | Release notes are extracted from the exact public version's nonempty changelog section. | Curate and date the first public version entry. |

Preview subscription, installation deferral while a task is active, and complete structured release-metadata consumption described above remain targets, not implemented guarantees. Verification records must identify the exact source and artifact under test.

## Policy decision log

| Date | Decision | Status |
| --- | --- | --- |
| 2026-10-07 | Separate public versions, Android installation order, and development identifiers; define release names, update channels, curated change logs, and validation evidence. | Guidance recorded; implementation status is described above. |
| 2026-10-23 | Separate public update precedence from development identifiers; require an existing exact tag, increasing code, signed artifact verification and draft-only preparation. | Implemented with local regression checks. GitHub execution, production signing and device acceptance remain separate. |
| 2026-11-02 | Add `CHANGELOG.zh.md` and publish update notes under English and Simplified Chinese headings so the app can follow its language. | Implemented from 0.1.3. Clients before 0.1.3 show both sections. |
| 2026-10-09 | Bind publication to an existing validated tag and its exact commit, gate signing on the shared Release checks, and prepare the fixed native build tools in CI. | Workflow and local script verification implemented. Linux execution and signed publication remain unverified; public version/channel and updater changes remain pending. |
| 2026-11-02 | Use English-only GitHub Release descriptions and separate `release_notes.en` and `release_notes.zh` fields in `release.json`; the app renders one language using its current locale. | Implemented for 0.1.4. Existing 0.1.3 clients display the English public body for this upgrade. |

Append a dated decision when these rules materially change, explaining the affected rule and its implementation status. Do not use this policy log as the application's release change log.
