#!/usr/bin/env bash
# Measures whether the reference app projects the panel onto its own display or whether the cluster is a head unit mirror.
#   ./tools/measure-panel.sh [serial]
set -uo pipefail
D="${1:-127.0.0.1:5555}"
OUT=/tmp/panel
mkdir -p "$OUT"

adb -s "$D" exec-out screencap -p > "$OUT/ivi.png" 2>/dev/null
adb -s "$D" shell 'fission_screencap -d 1 -p /data/local/tmp/m.png' >/dev/null 2>&1
adb -s "$D" pull /data/local/tmp/m.png "$OUT/cluster.png" >/dev/null 2>&1

a=$(md5sum "$OUT/ivi.png" | cut -d' ' -f1)
b=$(md5sum "$OUT/cluster.png" | cut -d' ' -f1)
echo "ivi=$a"
echo "cluster=$b"
if [ "$a" = "$b" ]; then echo "VERDICT: mirror (same image)"; else echo "VERDICT: different displays"; fi
echo "--- displays"
adb -s "$D" shell 'dumpsys display | grep -E "mDisplayId=|mHasContent|XDJAScreenProjection" | head -12'
