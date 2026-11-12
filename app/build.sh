#!/usr/bin/env bash
# Builds SharkLab into a signed APK without Gradle: aapt2 + javac + d8 + apksigner.
#
#   SDK=/opt/android-sdk ./build.sh
#
# android.jar comes from $SDK/platforms/android-<N>/android.jar (the highest one available).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-/opt/android-sdk}"
THEMES="${THEMES:-../themes}"
# AutoContainer only accepts sendInfo from com.xdja.clusterdemo (list in
# /system/etc/container_comm_cfg.json); without that name the panel keeps the BYD skin.
PKG="${PKG:-com.xdja.clusterdemo}"
OUT="${OUT:-out/sharklab-cluster.apk}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
KS="${LAB_KEYSTORE:-lab.keystore}"
PASS="${LAB_KEYSTORE_PASS:-sharklab}"

die() { echo "error: $*" >&2; exit 1; }
[ -n "$JAR" ] || die "android.jar not found in $SDK/platforms"
[ -x "$BT/aapt2" ] || die "aapt2 not found in $BT"
[ -x "$BT/d8" ] || die "d8 not found in $BT"
[ -x "$BT/apksigner" ] || die "apksigner not found in $BT"
command -v javac >/dev/null || die "javac missing"

rm -rf build && mkdir -p build/classes build/dex

echo "0/5 skins"
rm -rf build/assets
cp -r assets build/assets
mkdir -p build/assets/dash
if [ -d "$THEMES/dash" ]; then cp -r "$THEMES"/dash/* build/assets/dash/; else die "no themes in $THEMES/dash"; fi
find build/assets -name theme.json -delete
# opt-in only: embedding the workstation key puts a private key inside the APK
if [ "${EMBED_ADB_KEY:-0}" = "1" ] && [ -f "$HOME/.android/adbkey" ] && [ -f "$HOME/.android/adbkey.pub" ]; then
  cp "$HOME/.android/adbkey" build/assets/adbkey.pem
  cp "$HOME/.android/adbkey.pub" build/assets/adbkey.pub
  echo "   adb key embedded (EMBED_ADB_KEY=1)"
fi

echo "1/5 resources + manifest"
RES=""
if [ -d res ]; then
  "$BT/aapt2" compile --dir res -o build/res.zip
  RES="-R build/res.zip"
fi
PKG_FLAG=""
if [ -n "${PKG:-}" ]; then PKG_FLAG="--rename-manifest-package $PKG"; fi

"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$JAR" -A build/assets $RES --auto-add-overlay $PKG_FLAG \
  --min-sdk-version 26 --target-sdk-version 26 --version-code $(date +%Y%m%d%H%M | cut -c3-11) --version-name "$(date +%Y.%m.%d-%H%M)"

echo "2/5 javac"
javac -nowarn --release 8 -cp "$JAR" -d build/classes $(find src -name '*.java')

echo "3/5 d8"
"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')

echo "4/5 assemble APK"
cp build/base.apk build/app.apk
(cd build/dex && "$BT/../36.0.0/aapt2" version >/dev/null 2>&1 || true)
python3 - build/app.apk build/dex/classes.dex <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "5/5 align + sign"
"$BT/zipalign" -f -p 4 build/app.apk build/app-aligned.apk
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias lab \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SharkLab, O=Personal, C=BR" >/dev/null
fi
mkdir -p out
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "${OUT}" build/app-aligned.apk
"$BT/apksigner" verify "${OUT}" >/dev/null

"$BT/aapt2" dump badging "${OUT}" 2>/dev/null | head -2 || true
echo "APK: ${OUT}"
