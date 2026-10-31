#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-standard}"
case "$mode" in standard|release|platform) ;; *) echo "Usage: $0 [standard|release|platform]" >&2; exit 2 ;; esac
python3 scripts/verify_native.py
python3 scripts/verify_source.py
python3 scripts/verify_secrets.py
python3 -m unittest discover -s scripts/tests
./gradlew verifyArchitecture testDebugUnitTest :core:model:test :core:agent:test :core:knowledge:test :app:lintDebug :app:assembleDebug
python3 scripts/verify_native.py app/build/outputs/apk/debug/app-debug.apk
if [[ "$mode" == release ]]; then
    ./gradlew :app:assembleRelease
    python3 scripts/verify_native.py app/build/outputs/apk/release/app-release-unsigned.apk
fi
if [[ "$mode" == platform ]]; then ./gradlew connectedDebugAndroidTest; fi
