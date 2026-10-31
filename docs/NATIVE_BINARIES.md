# Native Binaries & Distribution Ledger

This document records native artifact sources, toolchains, ABI coverage and verification boundaries for the current Standard virtual-space build.

---

## 1. Retired Root input driver

`droidance_input` and its native build task have been removed. Current GUI input uses the paired shell Sidecar on the task's virtual display; there is no Root/Ultra execution route. `scripts/verify_native.py` rejects an APK that still contains `assets/bin/droidance_input`.

---

## 2. `libadb.so`

- **Purpose**: Android wireless-debugging SPAKE2 pairing and authenticated payload exchange. TLS transport remains in the existing Kotlin client.
- **Source**: Unchanged pairing JNI source from [Shizuku commit `2650830c5b099ae0dd34fedf614d4f592ca05d65`](https://github.com/RikkaApps/Shizuku/tree/2650830c5b099ae0dd34fedf614d4f592ca05d65), statically linked with [Google BoringSSL commit `571a7432a19592c620fa316abde47770dad4f82b`](https://github.com/google/boringssl/tree/571a7432a19592c620fa316abde47770dad4f82b).
- **Recipe and identities**: [`source.lock.json`](../third_party/libadb/source.lock.json) pins official source archives and upstream license hashes. [`build_android.py`](../third_party/libadb/build_android.py) builds all three ABIs; [`provenance.json`](../third_party/libadb/provenance.json) records recipe, tool, binary and license hashes. See the [rebuild instructions](../third_party/libadb/README.md) for the dependency relationship and commands.
- **Toolchain**: Android NDK `29.0.14206865`, SDK CMake `3.22.1`, API 28, static C++ runtime. This standalone recipe is independent of the Gradle NDK property and does not run during normal Gradle builds. The recorded build used the NDK's `darwin-x86_64` host tools on macOS; Linux and cross-host byte reproducibility have not been established.
- **Licenses**: Shizuku Apache-2.0 plus BoringSSL's OpenSSL, original SSLeay, ISC and fiat-crypto MIT terms. Full upstream licenses, the nested Fiat LICENSE, compiler-dependency and assembly-generator notices, acknowledgments and the NDK runtime notice are packaged in `assets/licenses/libadb/`. The build rejects missing notice coverage and records the source-to-notice mappings in its full receipt.
- **JNI contract**: The six upstream method registrations and `moe.shizuku.manager.adb.PairingContext` identity are preserved; the production Kotlin pairing protocol is unchanged.
- **Location**: `core/device/src/main/jniLibs/{ABI}/libadb.so`.
- **ABIs and SHA-256 digests**:
  - `arm64-v8a`: `574ccf5dfa75f07a2e2b505e7a3c52277444e6be3b3257a76252f7278bbf2c1d`
  - `armeabi-v7a`: `baa51dd3a8f0dae422e520b575b9b6f6f7b570592daa04c24ff52f76e05e2fb5`
  - `x86_64`: `49e9fb3176386e32fb0a5aed9ac538236cac2053fa362067e63158f6d41baf68`

All three rebuilt binaries passed source digest, JNI export, static linkage and 16 KB LOAD/RELRO checks. `NativeAdbPairingInstrumentedTest` covers client/server key agreement, bidirectional binary payloads and rejection of invalid authentication/peer data; its device execution must be reported separately. These checks do not establish APK ZIP alignment, end-to-end wireless-debugging pairing, shell reconnection, other-ABI runtime behavior or a 16 KB-page device run. The new source-built inputs replace binaries whose exact original source revision was not recorded; the new provenance does not identify those superseded binaries retroactively.

---

## 3. Artifact verification and native tool preparation

Keep one packaged input for each native artifact. The current repository-managed native input is `libadb.so`; updates must replace its binaries, license assets and provenance records together. Do not add a second packaged copy. Other native libraries may arrive through resolved Android dependencies and require a separate APK inventory.

`scripts/verify_native.py` checks the exact ABI inventory, source recipe, committed binary digests and required license hashes. When passed an APK it also checks packaged native bytes and license assets. Device-module `preBuild` and CI run the source check; standard and release validation also inspect the resulting APK. These checks do not establish device behavior or cross-host rebuild reproducibility.

See the [libadb rebuild instructions](../third_party/libadb/README.md) for source preparation and local tool requirements. Installing CI tools alone does not establish a successful rebuild or device behavior.

## 4. Removed OCR and image-processing dependencies

As of the 2026-10-11 documentation audit, the active Gradle configuration no longer declares PaddleOCR, ONNX Runtime or OpenCV. The `third_party/paddleocr/` and `third_party/opencv/` source recipes, OCR model assets and their dedicated license assets have been removed. Current screen observation uses screenshot input to the configured vision model.

Old build directories and APKs can still contain these components. Their notices remain applicable to those artifacts; removing current-source documentation does not alter historical distribution obligations. Do not use a stale APK as evidence of the current dependency set.

The current [third-party ledger](THIRD_PARTY.md) covers direct dependencies and artwork. A fresh release APK still needs its resolved dependency, native-library and packaged-notice inventories checked separately. This documentation update did not build or inspect a new APK.
