#!/usr/bin/env bash
# Instala e inicia o keepalive no carro (uma vez por arranque). Requer adb autorizado.
#   ./tools/install-keepalive.sh [serial]
set -euo pipefail
cd "$(dirname "$0")/.."
D="${1:-127.0.0.1:5555}"

adb -s "$D" push tools/keepalive.sh /data/local/tmp/sharklab.sh >/dev/null
adb -s "$D" shell 'chmod 755 /data/local/tmp/sharklab.sh; pkill -f sharklab.sh 2>/dev/null; setsid nohup /system/bin/sh /data/local/tmp/sharklab.sh >/dev/null 2>&1 &' >/dev/null
sleep 1
echo "keepalive pid: $(adb -s "$D" shell 'cat /data/local/tmp/sharklab.pid 2>/dev/null' | tr -d '\r')"
adb -s "$D" shell 'ps -A -o USER,PID,NAME | grep -c sharklab.sh' | tr -d '\r'
