#!/bin/bash
# Build the Linux host-side ReXGlue code generator used while cross-compiling the Android game.
# This is separate from build_sdk.sh, which builds the Android arm64 runtime libraries.
set -euo pipefail
source ~/android-tools/env.sh

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
bash "$ROOT/tools/android/prepare_sdk.sh"
SDK="$ROOT/rexglue-sdk"
BUILD="$SDK/out/build/linux-amd64"
PREFIX="$SDK/out/install/linux-amd64"
HOST_CC="${HOST_CC:-clang-20}"
HOST_CXX="${HOST_CXX:-clang++-20}"
JOBS="${JOBS:-4}"

command -v "$HOST_CC" >/dev/null 2>&1 || {
  echo "Host C compiler '$HOST_CC' not found. Install Clang 20 or set HOST_CC." >&2
  exit 1
}
command -v "$HOST_CXX" >/dev/null 2>&1 || {
  echo "Host C++ compiler '$HOST_CXX' not found. Install Clang 20 or set HOST_CXX." >&2
  exit 1
}

cmake -S "$SDK" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER="$HOST_CC" \
  -DCMAKE_CXX_COMPILER="$HOST_CXX" \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DREXGLUE_USE_VULKAN=ON \
  -DREXGLUE_ENABLE_TRACY=OFF \
  -DREXGLUE_ENABLE_PERF_COUNTERS=OFF \
  -DREXGLUE_BUILD_TESTS=OFF

cmake --build "$BUILD" --parallel "$JOBS"
cmake --install "$BUILD"

TOOL="$PREFIX/bin/rexglue"
if [ ! -x "$TOOL" ]; then
  echo "Host code generator was not installed at $TOOL." >&2
  exit 1
fi
"$TOOL" --version
echo "Linux host code generator ready: $TOOL"
