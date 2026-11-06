#!/usr/bin/env bash
# Build the pinned rootless Adreno Tools runtime for Android arm64.
# Output: build/android-turnip/install/lib/*.so
# This only builds/packages the loader runtime; renderer activation is a separate step.
set -euo pipefail

source ~/android-tools/env.sh
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/build/deps/libadrenotools"
BUILD="$ROOT/build/android-turnip"
PREFIX="$BUILD/install"
REV=8fae8ce254dfc1344527e05301e43f37dea2df80

command -v git >/dev/null
command -v cmake >/dev/null
command -v ninja >/dev/null
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the installed Android NDK}"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
test -f "$TOOLCHAIN"

mkdir -p "$(dirname "$SRC")" "$BUILD"
if [ ! -d "$SRC/.git" ]; then
  git clone --no-checkout --recurse-submodules https://github.com/bylaws/libadrenotools.git "$SRC"
fi
git -C "$SRC" fetch --tags origin
git -C "$SRC" checkout --detach "$REV"
git -C "$SRC" submodule sync --recursive
git -C "$SRC" submodule update --init --recursive
test -f "$SRC/lib/linkernsbypass/CMakeLists.txt"

cmake -S "$SRC" -B "$BUILD/cmake" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON -DGEN_INSTALL_TARGET=ON \
  -DCMAKE_INSTALL_PREFIX="$PREFIX"

cmake --build "$BUILD/cmake" --parallel "${JOBS:-4}"
cmake --install "$BUILD/cmake"

for lib in libadrenotools.so libmain_hook.so libfile_redirect_hook.so libgsl_alloc_hook.so libhook_impl.so; do
  test -s "$PREFIX/lib/$lib" || {
    echo "Expected Adreno Tools library missing: $PREFIX/lib/$lib" >&2
    exit 1
  }
done
echo "Adreno Tools arm64 libraries built at $PREFIX/lib"
echo "Pinned revision: $REV"
