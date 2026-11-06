#!/usr/bin/env bash
# Verify that an Android pack was generated for the exact shell in an APK.
# Usage: bash tools/android/verify_pack_shell.sh /path/to/GTAV.apk /path/to/gtav-android-pack.zip
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <apk> <android-pack.zip>" >&2
  exit 2
fi

APK=$1
PACK=$2
for f in "$APK" "$PACK"; do
  [[ -f "$f" ]] || { echo "Missing file: $f" >&2; exit 2; }
done

command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 2; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 2; }

echo "Checking pack archive..."
unzip -tq "$PACK"

manifest=$(unzip -p "$PACK" pack.txt)
expected=$(printf '%s\n' "$manifest" | awk '$1=="shell" {print $2; exit}')
[[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || {
  echo "pack.txt does not contain a valid 'shell <sha256>' entry." >&2
  exit 1
}

actual=$(unzip -p "$APK" lib/arm64-v8a/libgtav_fast.so | sha256sum | awk '{print $1}')
[[ "$actual" =~ ^[0-9a-fA-F]{64}$ ]] || {
  echo "APK does not contain lib/arm64-v8a/libgtav_fast.so." >&2
  exit 1
}

echo "Pack shell: $expected"
echo "APK shell:  $actual"
if [[ "${expected,,}" != "${actual,,}" ]]; then
  echo "MISMATCH: this APK cannot consume this pack." >&2
  exit 1
fi

echo "MATCH: APK shell and pack are compatible."
