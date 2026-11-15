#!/bin/bash
# ساخت APK برنامه پیام‌خوان بدون Gradle (aapt2 + javac + d8 + zipalign + apksigner)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK=/opt/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
SRC=$ROOT/app/src/main
BUILD=$ROOT/build
rm -rf "$BUILD"
mkdir -p "$BUILD/java" "$BUILD/classes" "$BUILD/dex"

echo "== 1) aapt2 compile =="
$BT/aapt2 compile --dir "$SRC/res" -o "$BUILD/res.zip"

echo "== 2) aapt2 link =="
$BT/aapt2 link -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$BUILD/java" \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 2 --version-name 1.1 \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "== 3) javac =="
javac -nowarn -encoding UTF-8 -source 11 -target 11 \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  "$BUILD/java/ir/payamkhan/R.java" \
  "$SRC/java/ir/payamkhan/MainActivity.java" \
  "$SRC/java/ir/payamkhan/NetTts.java"

echo "== 4) d8 =="
$BT/d8 --release --min-api 21 --lib "$PLATFORM" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

echo "== 5) pack =="
cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
cd "$BUILD/dex" && zip -q -X -0 "$BUILD/unaligned.apk" classes.dex && cd "$ROOT"

echo "== 6) zipalign =="
$BT/zipalign -f -p 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

echo "== 7) sign =="
if [ ! -f "$ROOT/tools/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/tools/debug.keystore" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=IR"
fi
$BT/apksigner sign --ks "$ROOT/tools/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$BUILD/PayamKhan-v1.1.apk" "$BUILD/aligned.apk"

echo "== 8) verify =="
$BT/apksigner verify --verbose "$BUILD/PayamKhan-v1.1.apk" | head -6
$BT/aapt dump badging "$BUILD/PayamKhan-v1.1.apk" | head -12
mkdir -p "$ROOT/release"
cp "$BUILD/PayamKhan-v1.1.apk" "$ROOT/release/PayamKhan-v1.1.apk"
ls -la "$ROOT/release/PayamKhan-v1.1.apk"
sha256sum "$ROOT/release/PayamKhan-v1.1.apk"
echo "BUILD OK"
