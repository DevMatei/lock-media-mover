#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
rm -rf "$OUT/classes" "$OUT/stubs" "$OUT"/*.apk "$OUT"/*.dex "$OUT"/*.zip
mkdir -p "$OUT/classes" "$OUT/stubs"

echo "> compiling Xposed API stubs (compile-only, not packaged)"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/stubs" \
    $(find stubs -name '*.java') 2>&1 | grep -v "^warning: \[options\]" || true

echo "> compiling module"
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -cp "$OUT/stubs" \
    -d "$OUT/classes" $(find src -name '*.java')

echo "> dexing"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"

echo "> packaging"
aapt package -f -M AndroidManifest.xml -A assets -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add -k unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "> signing"
KS=${KEYSTORE:-keystore/lockmediamover.p12}
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass lockmediamover \
        -keypass lockmediamover -alias lmm -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=devmatei, O=LockMediaMover" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:lockmediamover --ks-key-alias lmm \
    --min-sdk-version 31 --v2-signing-enabled true --v3-signing-enabled true --out "$OUT/LockMediaMover.apk" "$OUT/aligned.apk"
apksigner verify --min-sdk-version 31 "$OUT/LockMediaMover.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT"/*.idsig

echo "> magisk zip"
mkdir -p magisk/system/app/LockMediaMover
cp "$OUT/LockMediaMover.apk" magisk/system/app/LockMediaMover/LockMediaMover.apk
VER=$(grep '^version=' magisk/module.prop | cut -d= -f2)
(cd magisk && zip -qr9 "../$OUT/LockMediaMover-$VER-magisk.zip" . -x '.*')
echo "done: $OUT/LockMediaMover-$VER-magisk.zip"
