#!/bin/sh
# Dump the current phone screen's view tree to dumps/<name>.xml (+ compact summary).
export MSYS_NO_PATHCONV=1
# Usage: tools/dump.sh <name>
D="${DUMP_DIR:-dumps}"; mkdir -p "$D"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$D/$1.xml" >/dev/null 2>&1
python -I "$(dirname "$0")/summarize.py" "$D/$1.xml"
