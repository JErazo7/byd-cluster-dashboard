#!/usr/bin/env bash
# Builds, installs ON THE CAR and confirms the installed version is the one just generated.
#   ./tools/deploy.sh [serial]
set -uo pipefail
cd "$(dirname "$0")/.."
D="${1:-127.0.0.1:5555}"
APK=app/out/sharklab-cluster.apk
APK_REL=out/sharklab-cluster.apk
PKG=com.xdja.clusterdemo

echo "1) build"; ( cd app && PKG=$PKG OUT=$APK_REL ./build.sh >/dev/null 2>&1 ) || { echo "build failed"; exit 1; }
md5_local=$(md5sum "$APK" | cut -d' ' -f1)
echo "   local $md5_local"

echo "2) install"
adb -s "$D" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
adb -s "$D" push "$APK" /data/local/tmp/sl.apk >/dev/null 2>&1
adb -s "$D" shell "pm install -r -g --user 0 /data/local/tmp/sl.apk" >/dev/null 2>&1

echo "3) verify"
p=$(adb -s "$D" shell "pm path $PKG" | tr -d '\r' | sed 's/package://')
adb -s "$D" pull "$p" /tmp/_inst.apk >/dev/null 2>&1
md5_inst=$(md5sum /tmp/_inst.apk | cut -d' ' -f1)
echo "   installed $md5_inst"
if [ "$md5_local" != "$md5_inst" ]; then
  echo "   different -> uninstall and install again"
  adb -s "$D" shell "pm uninstall $PKG" >/dev/null 2>&1
  adb -s "$D" shell "pm install -g --user 0 /data/local/tmp/sl.apk" >/dev/null 2>&1
  p=$(adb -s "$D" shell "pm path $PKG" | tr -d '\r' | sed 's/package://')
  adb -s "$D" pull "$p" /tmp/_inst.apk >/dev/null 2>&1
  md5_inst=$(md5sum /tmp/_inst.apk | cut -d' ' -f1)
  echo "   reinstalled $md5_inst"
fi

echo "4) permissions and start"
adb -s "$D" shell "appops set $PKG SYSTEM_ALERT_WINDOW allow" >/dev/null 2>&1
adb -s "$D" shell "cmd notification allow_listener $PKG/com.sharklab.NavWatch" >/dev/null 2>&1
adb -s "$D" shell "am start -n $PKG/com.sharklab.MainActivity" >/dev/null 2>&1
sleep 6
echo "   installed version: $(adb -s "$D" shell "dumpsys package $PKG | grep versionName" | tr -d '\r')"
[ "$md5_local" = "$md5_inst" ] && echo "OK: the car is running the new build" || echo "WARNING: still different"
