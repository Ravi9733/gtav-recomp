#!/bin/bash
# Fetch the exact ReXGlue SDK revision used by the Android port and apply this repo's
# Android compatibility patch. Safe to re-run; refuses to overwrite local SDK edits.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SDK="$ROOT/rexglue-sdk"
PATCH="$ROOT/rexglue-sdk-gtav-linux.patch"
SDK_URL="https://github.com/rexglue/rexglue-sdk.git"
SDK_COMMIT="f5337cdc947ff6d4c4196737e2c807a48f2a1fc2"

if [ ! -f "$PATCH" ]; then
  echo "Missing Android SDK patch: $PATCH" >&2
  exit 1
fi

if [ ! -e "$SDK" ]; then
  echo "Cloning pinned ReXGlue SDK..."
  git clone --no-checkout "$SDK_URL" "$SDK"
elif [ ! -d "$SDK/.git" ]; then
  echo "Refusing to use $SDK: it exists but is not a Git checkout." >&2
  exit 1
fi

if [ -n "$(git -C "$SDK" status --porcelain)" ]; then
  # An already-applied project patch is expected; all other local edits are not.
  if ! git -C "$SDK" apply --reverse --check "$PATCH" >/dev/null 2>&1; then
    echo "Refusing to change ReXGlue SDK with local modifications. Review $SDK first." >&2
    exit 1
  fi
fi

CURRENT=$(git -C "$SDK" rev-parse HEAD 2>/dev/null || true)
if [ "$CURRENT" != "$SDK_COMMIT" ]; then
  if [ -n "$(git -C "$SDK" status --porcelain)" ]; then
    echo "SDK has local changes; cannot switch it to pinned commit $SDK_COMMIT." >&2
    exit 1
  fi
  echo "Checking out ReXGlue SDK $SDK_COMMIT..."
  git -C "$SDK" fetch --depth 1 origin "$SDK_COMMIT"
  git -C "$SDK" checkout --detach "$SDK_COMMIT"
fi

echo "Initializing pinned ReXGlue SDK submodules..."
git -C "$SDK" submodule update --init --recursive

if git -C "$SDK" apply --reverse --check "$PATCH" >/dev/null 2>&1; then
  echo "Android compatibility patch is already applied."
elif git -C "$SDK" apply --check "$PATCH"; then
  git -C "$SDK" apply "$PATCH"
  echo "Applied Android compatibility patch."
else
  echo "The Android compatibility patch does not apply cleanly to SDK $SDK_COMMIT." >&2
  exit 1
fi

echo "ReXGlue SDK source is ready at $SDK (commit $SDK_COMMIT)."
