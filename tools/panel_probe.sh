#!/usr/bin/env bash
# Captures the panel (display 1) and its windows to compare against the other app.
#   ./tools/panel_probe.sh <label> [serial]
set -uo pipefail
L="${1:-probe}"; D="${2:-127.0.0.1:5555}"
O="/tmp/panel-$L"
adb -s "$D" exec-out screencap -d 1 -p > "$O.png" 2>/dev/null
adb -s "$D" shell 'dumpsys window windows' > "$O.windows.txt" 2>/dev/null
adb -s "$D" shell 'dumpsys display | grep -E "DisplayDeviceInfo.*fission|mDisplayId=1"' > "$O.display.txt" 2>/dev/null
echo "== $L"
awk '/^  Window #/{w=$0} /mDisplayId=1/{print w; f=1; next} f&&/mOwnerUid/{print "  "$0; f=0}' "$O.windows.txt" | head -20
awk '/EvSound painel/{f=1} f&&/mAttrs/{print "  "$0; f=0}' "$O.windows.txt" | head -6
ls -l "$O.png"
