# Wireless ADB pairing JNI source recipe

The application packages one `core/device/src/main/jniLibs/<ABI>/libadb.so` for each of `arm64-v8a`, `armeabi-v7a` and `x86_64`. These checked-in inputs are built by this recipe; Gradle does not download or rebuild them during normal application builds.

`source.lock.json` fixes official source archives, their commits, byte sizes and SHA-256 digests. The JNI implementation is the unchanged `adb_pairing.cpp` from Shizuku commit `2650830c5b099ae0dd34fedf614d4f592ca05d65` (v13.6.0). BoringSSL commit `571a7432a19592c620fa316abde47770dad4f82b` is the upstream source pinned by the BoringSSL Android `20250114` dependency used by that Shizuku revision. The crypto library is compiled locally from this source; no BoringSSL AAR or separately packaged crypto runtime is used.

The dependency relationship is recorded in [Shizuku's pinned dependency declaration](https://github.com/RikkaApps/Shizuku/blob/2650830c5b099ae0dd34fedf614d4f592ca05d65/manager/build.gradle#L185), the [published BoringSSL Android POM](https://repo.maven.apache.org/maven2/io/github/vvb2060/ndk/boringssl/20250114/boringssl-20250114.pom), and the [`20250114` package source tree](https://api.github.com/repos/vvb2060/BoringSSL_Android/git/trees/20b87e2e25a793531c9cd651c629c62976b42e2b?recursive=1). In that tree, `boringssl/src/main/native/src` is a gitlink to the locked Google BoringSSL commit; its [submodule declaration](https://github.com/vvb2060/BoringSSL_Android/blob/20b87e2e25a793531c9cd651c629c62976b42e2b/.gitmodules) identifies the official Google repository.

The local CMake recipe builds only the crypto and pairing JNI targets, with section garbage collection, a static NDK C++ runtime, and explicit 16 KB maximum/common page sizes. It omits Shizuku's unrelated starter and utility code. The six registered JNI methods and `moe.shizuku.manager.adb.PairingContext` identity are unchanged. This establishes provenance for the new binaries; it does not retrospectively identify the superseded binaries' exact source revision.

## Rebuild

Install SDK CMake `3.22.1` and NDK `29.0.14206865`. This NDK is independent of the repository's Gradle NDK property; do not change `droidanceNdkVersion` for this recipe. Python 3.9+ and a macOS or Linux NDK host are required. The initial verified source downloads total approximately 50 MB and extract to approximately 136 MB.

```sh
python3 third_party/libadb/build_android.py \
  --sdk "$ANDROID_HOME" \
  --ndk "$ANDROID_HOME/ndk/29.0.14206865" \
  --work /tmp/droidance-libadb-build \
  --output /tmp/droidance-libadb-candidate \
  --jobs 4
```

Choose a fresh output directory. Downloads are checked before extraction; source archives and caches reject traversal, symlinks and unexpected files. Every run rechecks cached source bytes, clears the ABI build directories, and removes ambient compiler/linker overrides from the build environment. Build products stay outside the source checkout. `receipt.json` records the commands, actual host tool hashes, source identities, output hashes, dynamic symbols and ELF program headers. The output directory is published after all ABIs and license assets pass validation.

The candidate contains `jniLibs/`, `assets/licenses/libadb/`, `provenance.json` and the full build receipt. After reviewing and testing an update, replace the three existing packaged libraries, the corresponding license asset directory, and this directory's `provenance.json` together. Keep the full receipt with the validation artifacts. Do not add a second JNI packaging source. Host-specific output hashes may differ; the recipe does not claim cross-host byte reproducibility.

`scripts/verify_native.py` checks the APK against the committed source-build provenance, exact ABI set, current recipe identity and required license hashes, in addition to comparing APK bytes with the three packaged inputs.

## Licenses

The APK retains Shizuku's Apache-2.0 license, BoringSSL's full upstream LICENSE (including OpenSSL, original SSLeay, ISC and fiat-crypto MIT terms), the complete nested Fiat LICENSE, explicit OpenSSL/SSLeay acknowledgments, and the matching NDK runtime notice. Source notices cover compiled files and the compiler's Ninja dependency records, including headers and amalgamated `.cc.inc` files. Generated assembly is traced to its Perl input using the pinned upstream `build.json` declarations; its generator notices are retained. Generated Fiat files without a notice header are explicitly covered by their nested LICENSE, and hand-written public-domain assembly retains its attribution.

Path aliases and relative compilation database paths are resolved before matching sources. Missing compiled sources, compiler dependencies or unattributed files cause the build to fail. The full receipt records each ABI's dependency paths and notice mappings; provenance records union coverage counts. These checks supplement the locked upstream license hashes and all generated asset hashes. They are separate from licenses of unrelated APK dependencies.

## Validation boundaries

```sh
python3 -m unittest discover -s third_party/libadb/tests -v
python3 scripts/verify_native.py
```

`com.droidance.air.NativeAdbPairingInstrumentedTest` loads the packaged JNI library and exercises client/server SPAKE2 agreement, bidirectional binary payloads, authenticated-ciphertext rejection, password mismatch and oversized peer messages. Its server role uses the existing private JNI constructor from the test only; production Kotlin and pairing protocol are unchanged. It does not change device pairing settings or contact an ADB server.

Compilation, ELF checks and these native tests do not establish end-to-end Android wireless-debugging pairing, shell reconnect behavior, other-ABI runtime compatibility, or a 16 KB-page device run. Report those checks separately when performing a release validation.
