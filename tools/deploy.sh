#!/bin/sh
# Build + install without the HyperOS USB-install prompt (pm install from device storage).
# Usage: tools/deploy.sh            -> debug build (has the adb-only DebugReceiver test hook)
#        tools/deploy.sh release    -> release build (daily use, no test hook)
# The very first install must use `adb install` (tap Install on the phone).
set -e
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
if [ "$1" = "release" ]; then T=assembleRelease; A=app/build/outputs/apk/release/app-release.apk
else T=assembleDebug; A=app/build/outputs/apk/debug/app-debug.apk; fi
./gradlew $T --console=plain -q
adb push "$A" /data/local/tmp/sh.apk >/dev/null
adb shell pm install -r /data/local/tmp/sh.apk
adb shell rm /data/local/tmp/sh.apk
