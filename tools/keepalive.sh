#!/system/bin/sh
# SharkLab — keeps the skin projected on the cluster panel.
#
# Runs as shell (uid 2000) and therefore can talk to AutoContainer, which the app
# cannot. Re-applies the head unit mirror (cmd 16) every 20 s while our app is
# running — so the panel comes back on its own after switching apps or restarting SharkLab.
#
# Installed by tools/install-keepalive.sh.
# Stop: kill "$(cat /data/local/tmp/sharklab.pid)"

PIDFILE=/data/local/tmp/sharklab.pid
INTERVAL=20

echo $$ > "$PIDFILE"

while true; do
  if [ -n "$(pidof com.sharklab)" ]; then
    service call AutoContainer 2 i32 1000 i32 16 s16 "" >/dev/null 2>&1
  fi
  sleep $INTERVAL
done
