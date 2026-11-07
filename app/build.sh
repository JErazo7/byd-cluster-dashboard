#!/usr/bin/env bash
# Compila o SharkLab em APK assinado, sem Gradle: aapt2 + javac + d8 + apksigner.
#
#   SDK=/opt/android-sdk ./build.sh
#
# android.jar vem de $SDK/platforms/android-<N>/android.jar (o maior que existir).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-/opt/android-sdk}"
THEMES="${THEMES:-../themes}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
KS="${LAB_KEYSTORE:-lab.keystore}"
PASS="${LAB_KEYSTORE_PASS:-sharklab}"

die() { echo "erro: $*" >&2; exit 1; }
[ -n "$JAR" ] || die "android.jar não encontrado em $SDK/platforms"
[ -x "$BT/aapt2" ] || die "aapt2 não encontrado em $BT"
[ -x "$BT/d8" ] || die "d8 não encontrado em $BT"
[ -x "$BT/apksigner" ] || die "apksigner não encontrado em $BT"
command -v javac >/dev/null || die "falta javac"

rm -rf build && mkdir -p build/classes build/dex

echo "0/5 skins"
rm -rf build/assets
cp -r assets build/assets
mkdir -p build/assets/dash
if [ -d "$THEMES/dash" ]; then cp -r "$THEMES"/dash/* build/assets/dash/; else die "sem temas em $THEMES/dash"; fi
find build/assets -name theme.json -delete
mkdir -p build/assets/engine
if [ -d ../engine ]; then cp ../engine/*.js build/assets/engine/; else die "sem ../engine"; fi

echo "1/5 recursos + manifesto"
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$JAR" -A build/assets \
  --min-sdk-version 26 --target-sdk-version 29 --version-code 1 --version-name 0.1

echo "2/5 javac"
javac -nowarn --release 8 -cp "$JAR" -d build/classes $(find src -name '*.java')

echo "3/5 d8"
"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')

echo "4/5 montar APK"
cp build/base.apk build/app.apk
(cd build/dex && "$BT/../36.0.0/aapt2" version >/dev/null 2>&1 || true)
python3 - build/app.apk build/dex/classes.dex <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "5/5 alinhar + assinar"
"$BT/zipalign" -f -p 4 build/app.apk build/app-aligned.apk
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias lab \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SharkLab, O=Personal, C=BR" >/dev/null
fi
mkdir -p out
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out out/sharklab.apk build/app-aligned.apk
"$BT/apksigner" verify out/sharklab.apk >/dev/null

"$BT/aapt2" dump badging out/sharklab.apk | head -2
echo "APK: out/sharklab.apk"
