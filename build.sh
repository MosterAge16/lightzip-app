#!/usr/bin/env bash
# ============================================================
# 轻解压 一键构建（无 Gradle：aapt2 → javac → d8 → jar → zipalign → apksigner）
# 引擎二进制在 engines/{lib7zz.so,libunrar.so}
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}"
[ -d "$SDK" ] || { echo "❌ 未找到 Android SDK"; exit 1; }
SDK="$(cygpath -u "$SDK" 2>/dev/null || echo "$SDK")"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-*/ | sort -V | tail -1)"
AJ="$PLAT/android.jar"

[ -f engines/lib7zz.so ]   || { echo "❌ 缺少 engines/lib7zz.so";   exit 1; }
[ -f engines/libunrar.so ] || { echo "❌ 缺少 engines/libunrar.so"; exit 1; }

MIN=21
TGT=35
VCODE=11
VNAME=1.2.8

rm -rf build
mkdir -p build/gen build/classes build/dex build/lib/arm64-v8a dist

echo "[1/7] 生成图标"
javac -encoding UTF-8 -d build/tools tools/IconGen.java
java -cp build/tools IconGen res

echo "[2/7] 编译资源 (aapt2 compile)"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[3/7] 链接资源 (aapt2 link)"
"$BT/aapt2.exe" link -o build/app.unsigned.apk \
    -I "$AJ" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    --java build/gen \
    --auto-add-overlay \
    --min-sdk-version "$MIN" --target-sdk-version "$TGT" \
    --version-code "$VCODE" --version-name "$VNAME"

echo "[4/7] 编译 Java (javac)"
# shellcheck disable=SC2046
javac -encoding UTF-8 -source 1.8 -target 1.8 \
    -bootclasspath "$AJ" -classpath "$AJ" \
    -d build/classes $(find src build/gen -name '*.java')

echo "[5/7] 转 DEX (d8)"
# shellcheck disable=SC2046
"$BT/d8.bat" --release --lib "$AJ" --min-api "$MIN" \
    --output build/dex $(find build/classes -name '*.class')

echo "[6/7] 组装 APK"
JAVA_HOME_DIR="$(java -XshowSettings:properties -version 2>&1 \
    | sed -n 's/.*java\.home = \(.*\)$/\1/p' | head -n 1)"
JAR="$JAVA_HOME_DIR/bin/jar.exe"
[ -f "$JAR" ] || { echo "❌ 未找到 jar 工具"; exit 1; }
cp engines/lib7zz.so build/lib/arm64-v8a/lib7zz.so
cp engines/libunrar.so build/lib/arm64-v8a/libunrar.so
"$JAR" uf build/app.unsigned.apk -C build/dex classes.dex -C build lib

echo "[7/7] 对齐 + 签名"
"$BT/zipalign.exe" -f 4 build/app.unsigned.apk build/app.aligned.apk

KS=release.jks
if [ ! -f "$KS" ]; then
    echo "  首次构建，生成正式签名 release.jks（口令 android，务必保管好以便日后升级）"
    "$JAVA_HOME_DIR/bin/keytool.exe" -genkeypair -keystore "$KS" -storetype PKCS12 \
        -storepass android -keypass android \
        -alias lightzip -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=LightZip, OU=Personal, O=JQ, C=CN"
fi
"$BT/apksigner.bat" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version "$MIN" \
    --out dist/LightZip.apk build/app.aligned.apk

echo "---- 校验 ----"
"$BT/apksigner.bat" verify --print-certs dist/LightZip.apk | head -n 3
"$BT/aapt.exe" dump badging dist/LightZip.apk | grep -E '^(package|application-label|sdkVersion|native-code)' || true
ls -l dist/LightZip.apk
echo "✅ 构建完成：$(pwd)/dist/LightZip.apk"
