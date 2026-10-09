#!/usr/bin/env bash
# Build the JBC B·IRON Android app and (optionally) install it.
# Last modified: 2026-10-09--0107
#
# Gradle can't build directly on the SMB/NAS path, so this mirrors the source
# to a local dir, builds there with the Android Studio JBR (JDK 21), copies the
# APK back to ./out, and installs to the connected phone. Windows twin: build.ps1.
#
#   ./build.sh            # build + copy APK to ./out
#   ./build.sh install    # build + install to the connected device via adb
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HOME/builds/jbc-android"
GRADLE="$HOME/builds/tools/gradle-8.11.1/bin/gradle"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export GRADLE_USER_HOME="$HOME/builds/.gradle-home"
SDK="$HOME/Library/Android/sdk"

mkdir -p "$BUILD"
rsync -a --delete --exclude 'build/' --exclude '.gradle/' --exclude 'out/' "$SRC/" "$BUILD/"
echo "sdk.dir=$SDK" > "$BUILD/local.properties"

( cd "$BUILD" && "$GRADLE" :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain )

APK="$BUILD/app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$SRC/out"
cp "$APK" "$SRC/out/jbc-biron-debug.apk"
echo "APK -> $SRC/out/jbc-biron-debug.apk"

if [ "${1:-}" = "install" ]; then
    "$SDK/platform-tools/adb" install -r "$APK"
    echo "installed."
fi
