# Third-Party Components & Artwork

Current-source documentation audit: **2026-10-12**. This ledger follows the
active Gradle declarations, retained assets and native source locks. It is
not a complete resolved dependency report or a certification of release
license compliance. The project license is [Apache-2.0](../LICENSE); upstream
components and brand marks retain their own terms.

## Direct runtime dependencies

Versions come from [the version catalog](../gradle/libs.versions.toml) and
module build files. Test-only and build-tool dependencies are outside this
runtime summary. The [resolved release dependency inventory](RELEASE_DEPENDENCIES.md)
lists transitive modules and their available upstream license declarations.

| Component | Declared version | Purpose | Upstream license / source |
| --- | --- | --- | --- |
| AndroidX, Compose, Room, WorkManager | Per version catalog; Compose BOM `2024.12.01` | Android UI, persistence and scheduling | Apache-2.0; [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin, Coroutines, Serialization | `2.1.0`, `1.9.0`, `1.7.3` | Language and data handling | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin), [Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Serialization](https://github.com/Kotlin/kotlinx.serialization) |
| Dagger / Hilt, Javax Inject | `2.53.1`, `1` | Dependency injection | Apache-2.0; [Dagger](https://github.com/google/dagger), [Javax Inject](https://github.com/javax-inject/javax-inject) |
| OkHttp | `4.12.0` | HTTP transport | Apache-2.0; [OkHttp](https://github.com/square/okhttp) |
| Mozilla Rhino | `1.8.0` | Isolated JavaScript interpreter | MPL-2.0; [Rhino](https://github.com/mozilla/rhino/tree/Rhino1_8_0_Release), [retained license](../app/src/main/assets/licenses/rhino-MPL-2.0.txt) |
| dadb | `1.2.7` | ADB transport | Apache-2.0; [dadb](https://github.com/mobile-dev-inc/dadb) |
| JetBrains Markdown | `0.7.3` | Markdown parsing | Apache-2.0; [Markdown](https://github.com/JetBrains/markdown) |
| SnakeYAML Engine | `3.0.1` | YAML parsing | Apache-2.0; [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml-engine) |
| Coil | `2.7.0` | Image loading | Apache-2.0; [Coil](https://github.com/coil-kt/coil) |
| Multiplatform Markdown Renderer | `0.27.0-rc02` | Message rendering | Apache-2.0; [Renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) |

Root [NOTICE](../NOTICE) summarizes attributions. The APK retains dedicated
license assets for Rhino, Lucide, Lobe Icons and libadb. The application
packaging configuration merges common dependency license/notice resource
paths. Neither that configuration nor this table proves that every resolved
artifact includes all required texts; inspect the final distribution and
supplement missing notices before release.

## Native pairing library

`libadb.so` is retained for `arm64-v8a`, `armeabi-v7a` and `x86_64`.
Its Shizuku pairing source, statically linked BoringSSL, NDK runtime notices
and locked build identities are documented in [the native ledger](NATIVE_BINARIES.md)
and [the source recipe](../third_party/libadb/README.md).

The [source lock](../third_party/libadb/source.lock.json) and
[provenance](../third_party/libadb/provenance.json) govern binary and license
updates. A separate release APK inventory must cover any native libraries
introduced by Maven dependencies.

## Icons and project artwork

| Retained assets | Source record | License / review status |
| --- | --- | --- |
| Functional vectors in `DroidanceIcons.kt` | Lucide commit `1fae58d0a9c661a338036838caf3399b5507bab6`; paths adapted to the project's stroke style | ISC with retained Feather MIT attribution; [full text](../app/src/main/assets/licenses/lucide-LICENSE.txt) |
| 18 provider brands, light/dark resources | Lobe Icons `1.93.0`; [catalog](../app/src/main/assets/provider_icons/catalog.json) | MIT text retained; [source and provenance limits](../app/src/main/assets/provider_icons/NOTICE.md). Brand rights remain separate. |
| Launcher, mascot head/body and hand pointer | Project image-generation and editing records | Project-produced artwork, separate from upstream brand icons. Generation records do not certify third-party rights clearance. |

Current project image paths are:

- `app/src/main/res/drawable-nodpi/droidance_launcher_icon.png`
- `app/src/main/res/drawable-nodpi/droidance_mascot_head.png`
- `app/src/main/res/drawable-nodpi/droidance_mascot_body.png`
- `app/src/main/assets/robot_hand_pointer.png`

The edited demonstration clips in `assets/demos/` show third-party app interfaces for illustration; those interfaces and marks remain with their owners. The source package does not include the website or historical render and device records. Review rights in the retained project artwork and provider brand assets before distribution.

## Removed from the current source

PaddleOCR, PP-OCRv6 model assets, ONNX Runtime, OpenCV and the Root input
driver are absent from the active build and their dedicated source/assets
have been removed. They are no longer listed as current components in
`NOTICE`. Stale build outputs and older APKs can still contain them; retain
the relevant licenses when distributing those artifacts.

An unused feature is not by itself a reason to delete its notices: removal
is based on the actual distributed files and dependencies. Retained brand
resources and the Rhino interpreter therefore remain in this ledger.

## Attribution under the project license

The unmodified [Apache-2.0 license, section 4](https://www.apache.org/licenses/LICENSE-2.0)
requires a license copy for redistribution, notices on modified files,
preservation of relevant source copyright and attribution notices, and a
readable copy of relevant `NOTICE` attributions in an allowed location.
It does not require a prominent homepage credit from every private user or
clone. The project's suggested README credit is a community request, not
an additional license condition; `NOTICE` does not change the license.

Project-owned source uses SPDX copyright/license headers. Mixed-source and
upstream files keep their own attribution. The fixed libadb build recipes use
adjacent `.license` sidecars so their recorded byte identities remain valid.
`GenerateProjectNotices` packages the root LICENSE and NOTICE into both APKs;
Settings > About exposes these and the retained upstream notices. CI checks
source headers, project provenance and exact packaged notice bytes. These
checks do not certify all transitive dependencies, trademarks or historical
release obligations.
