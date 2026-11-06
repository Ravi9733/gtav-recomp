#!/bin/bash
# Package build/android/libmain.so + the SDK's shared libraries into a debug-signed APK, without
# Gradle: javac (SDL's Java + GameActivity) -> d8 -> aapt2 link -> zip libs -> zipalign -> apksigner.
# Usage: tools/android/build_apk.sh [install]   -> build/android/apk/gtav-recomp.apk
# RELEASE=1: the public APK with no game code (build/android-dll, GTAV_GAME_DLL=ON: the libmain.so
# shim + libgtav_fast.so; the player's libgtav_game.so comes from the PC's Build for Android), not
# debuggable -> build/android/apk/gtav-recomp-release.apk
set -euo pipefail
source ~/android-tools/env.sh
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP=$ROOT/tools/android/app
OUT=$ROOT/build/android/apk
SDK_LIB=$ROOT/rexglue-sdk/out/install/android-arm64/lib
SDL_JAVA=$ROOT/rexglue-sdk/thirdparty/sdl3/android-project/app/src/main/java
BT=$ANDROID_HOME/build-tools/36.0.0
JAR=$ANDROID_HOME/platforms/android-36/android.jar
NDK_LIBCXX=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so
KEY=$HOME/android-tools/debug.keystore

mkdir -p "$OUT"
cd "$OUT"
rm -rf classes stage && mkdir -p classes stage/lib/arm64-v8a

mapfile -d '' JAVA_SOURCES < <(find "$SDL_JAVA" "$APP/java" -name '*.java' -print0)
if [ "${#JAVA_SOURCES[@]}" -eq 0 ]; then
  echo "No Android Java sources found." >&2
  exit 1
fi
if ! javac --release 17 -nowarn -classpath "$JAR" -d classes "${JAVA_SOURCES[@]}" > javac.log 2>&1; then
  cat javac.log >&2
  exit 1
fi
grep -v "^Note:" javac.log || true
mapfile -d '' CLASS_FILES < <(find classes -name '*.class' -print0)
if [ "${#CLASS_FILES[@]}" -eq 0 ]; then
  echo "javac produced no class files." >&2
  exit 1
fi
"$BT/d8" --release --min-api 30 --lib "$JAR" --output stage "${CLASS_FILES[@]}"

# Optional on-device setup tools. These are packaged as assets and must be copied to
# app-private executable storage by SetupActivity before they can be launched.
if [ "${ON_DEVICE_TOOLS:-0}" = 1 ]; then
  : "${REXGLUE_ANDROID_TOOL:?Set REXGLUE_ANDROID_TOOL to the verified arm64 Android rexglue executable}"
  test -x "$REXGLUE_ANDROID_TOOL"
  test -x "$ROOT/build/android-llvm/out/clang"
  test -x "$ROOT/build/android-llvm/out/lld"
  mkdir -p stage/assets/tools
  install -m 755 "$REXGLUE_ANDROID_TOOL" stage/assets/tools/rexglue
  install -m 755 "$ROOT/build/android-llvm/out/clang" stage/assets/tools/clang++
  install -m 755 "$ROOT/build/android-llvm/out/lld" stage/assets/tools/ld.lld
fi

MANIFEST=$APP/AndroidManifest.xml
NAME=gtav-recomp
# The setup screen's library (SetupActivity) is in both: it has no game code.
LIBS=("$ROOT/build/android/libmain.so" "$ROOT/build/android-dll/libgtav_setup.so")
if [ "${RELEASE:-0}" = 1 ]; then
  NAME=gtav-recomp-release
  LIBS=("$ROOT/build/android-dll/libmain.so" "$ROOT/build/android-dll/libgtav_fast.so"
        "$ROOT/build/android-dll/libgtav_setup.so")
  sed 's/ *android:debuggable="true"//' "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.release.xml"
  MANIFEST=$OUT/AndroidManifest.release.xml
fi
"$BT/aapt2" link -o base.apk --manifest "$MANIFEST" -I "$JAR" \
  --min-sdk-version 30 --target-sdk-version 34

cp "${LIBS[@]}" "$SDK_LIB/librexruntime.so" "$SDK_LIB/librexgpu-xenos.so" \
   "$NDK_LIBCXX" stage/lib/arm64-v8a/

# Optional rootless Turnip runtime. This packages the loader and hook libraries only;
# the renderer must explicitly initialize adrenotools before Plume creates Vulkan.
if [ "${TURNIP:-0}" = 1 ]; then
  TURNIP_LIB="$ROOT/build/android-turnip/install/lib"
  for lib in libadrenotools.so libmain_hook.so libfile_redirect_hook.so libgsl_alloc_hook.so libhook_impl.so; do
    test -s "$TURNIP_LIB/$lib" || {
      echo "Missing $TURNIP_LIB/$lib. Run tools/android/build_turnip_loader.sh first." >&2
      exit 1
    }
    cp "$TURNIP_LIB/$lib" stage/lib/arm64-v8a/
  done
fi
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug stage/lib/arm64-v8a/*.so
# Repack the aapt2 base APK so resources.arsc is explicitly STORED (not deflated).
# Android 11+ rejects compressed resources.arsc with INSTALL_FAILED_INVALID_APK (-124).
rm -rf "$OUT/repack" "$OUT/repacked.apk" "$OUT/unaligned.apk" "$OUT/aligned.apk"
mkdir -p "$OUT/repack"
unzip -q base.apk -d "$OUT/repack"
test -s "$OUT/repack/resources.arsc"
(cd "$OUT/repack" && zip -q -X -r "$OUT/repacked.apk" . -x 'resources.arsc' 'lib/*')
(cd "$OUT/repack" && zip -q -X -0 "$OUT/repacked.apk" resources.arsc)
(cd stage && zip -q -X "$OUT/repacked.apk" classes.dex && zip -q -X -0 -r "$OUT/repacked.apk" lib && if [ -d assets ]; then zip -q -X -r "$OUT/repacked.apk" assets; fi)

# Fail the build before signing if the resource table is compressed or duplicated.
python3 - "$OUT/repacked.apk" <<'PY'
import sys, zipfile
apk = sys.argv[1]
with zipfile.ZipFile(apk) as z:
    entries = [i for i in z.infolist() if i.filename == "resources.arsc"]
    if len(entries) != 1:
        raise SystemExit(f"Expected exactly one resources.arsc, found {len(entries)}")
    if entries[0].compress_type != zipfile.ZIP_STORED:
        raise SystemExit("resources.arsc must be stored without compression")
    bad_libs = [i.filename for i in z.infolist()
                if i.filename.startswith("lib/") and i.filename.endswith(".so")
                and i.compress_type != zipfile.ZIP_STORED]
    if bad_libs:
        raise SystemExit("Native libraries must be stored: " + ", ".join(bad_libs))
print("APK ZIP check passed: resources.arsc and native libraries are STORED")
PY

"$BT/zipalign" -f -P 16 4 repacked.apk aligned.apk
"$BT/zipalign" -c -P 16 4 aligned.apk
if [ ! -f "$KEY" ]; then
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
  --out $NAME.apk aligned.apk
rm -f unaligned.apk aligned.apk base.apk
ls -la $NAME.apk
if [ "${1:-}" = install ]; then
  adb install -r $NAME.apk
fi
