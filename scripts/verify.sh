#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-standard}"
case "$mode" in standard|release|platform) ;; *) echo "Usage: $0 [standard|release|platform]" >&2; exit 2 ;; esac
if [[ "$mode" == platform && ! -d app/src/androidTest ]]; then
    echo "Platform tests are local-only and are not included in the public source tree." >&2
    exit 2
fi
python3 scripts/verify_native.py
python3 scripts/verify_source.py
python3 scripts/verify_secrets.py
if [[ -d scripts/tests ]]; then python3 -m unittest discover -s scripts/tests; fi
if [[ -d app/src/test || -d core/agent/src/test ]]; then
    ./gradlew testDebugUnitTest :core:model:test :core:agent:test :core:knowledge:test
else
    echo "No local test sources present; running public source, lint and build checks."
fi
./gradlew verifyArchitecture :app:lintDebug :app:assembleDebug
python3 scripts/verify_native.py app/build/outputs/apk/debug/app-debug.apk
if [[ "$mode" == release ]]; then
    ./gradlew :app:assembleRelease
    python3 scripts/verify_native.py app/build/outputs/apk/release/app-release-unsigned.apk
fi
if [[ "$mode" == platform ]]; then
    ./gradlew connectedDebugAndroidTest -PdroidanceTestRunner=com.droidance.settings.SettingsRegressionTestRunner
fi
