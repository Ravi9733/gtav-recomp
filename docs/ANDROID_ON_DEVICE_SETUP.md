# Android on-device setup: implementation map

This document describes the work required before the public Android APK can install from the
player's Disc 1, Disc 2 and TU27 alone. It is an engineering plan, not a claim that these features
already work.

## Current behavior

The public APK currently has two independent setup paths:

1. `SetupActivity` imports `gtav-android-pack.zip`. The pack contains the player's
   `libgtav_game.so`, Adreno SPIR-V files, `expected.txt`, and `pack.txt`.
2. `libgtav_setup.so` runs `RunDataInstall`, which copies and verifies the game data from the
   three user-selected source files. It does not generate or compile game code.

The desktop `RunAndroidBuild` path creates the pack. It runs code generation, checks generated
functions, compiles the generated C++, links the Android shared library, translates shaders, and
writes the pack. The existing `tools/android/build_llvm.sh` only builds Clang and LLD; it does not
make the complete setup pipeline run on Android.

## Required implementation stages

### 1. Android-native process execution

`install.cpp` currently defines `LOADER_NO_SPAWN` for Android. This is intentional: the Android
setup JNI currently exposes only the data-copy operation, and the normal `Child` process runner is
not available to the Android build. Before reusing the desktop installer, implement and test an
Android process runner that:

- launches only app-private executables from the app's native-library directory;
- passes an explicit working directory and argument vector (never a shell command string);
- redirects stdout/stderr to per-stage log files;
- supports cancellation, exit-status reporting, and bounded waits;
- does not execute files from shared storage.

### 2. Android code generator

Build the exact ReXGlue/Xenos recompilation generator for `arm64-v8a` and Android API 30.
Verify all runtime dependencies and generated output against the desktop generator on the same
user-supplied TU27 installation. The generated source set is large (hundreds of C++ files), so
memory use, temporary storage, and resumability must be measured on real devices.

### 3. Android compiler and linker

The existing LLVM build script targets Android, but its output is not currently packaged or called
by `SetupActivity`. Integrate Clang and LLD only after verifying that the binaries start on Android
API 30, can access the app-private sysroot/link inputs, and can compile/link a small arm64 smoke
test. Then compile the generated game objects with the exact freestanding flags and link inputs
used by desktop `RunAndroidBuild`. Do not assume that copying executables into an APK is enough:
the runtime paths, permissions, linker inputs, and process-launch restrictions all need tests.

### 4. On-device shader pipeline

The desktop path uses XenosRecomp plus DXC and Android-specific Adreno fixes to turn the player's
shader cache into SPIR-V. Port/build both tools for Android or implement a tested in-process
equivalent. The output must pass the same Adreno validation as the desktop pipeline. A prebuilt
shader folder cannot be assumed to cover shaders discovered later by the game.

### 5. Setup integration and recovery

Only after stages 1–4 pass independently:

- include the non-game reference manifest (`expected.txt`) in the public APK;
- extend setup to accept the three user-owned source files without a game pack;
- run copy, codegen, verification, compile, link, and shader stages with separate progress/log
  reporting;
- write outputs to staging paths and atomically promote them only after validation;
- preserve a previously working install if a new build fails;
- keep generated game code and user game data out of public releases.

## Acceptance checks

A release must not be called PC-free until all of these pass on a real Android arm64 device:

- Fresh install from only Disc 1, Disc 2 and TU27 selected in the app; no PC-created pack.
- Successful codegen, source verification, compile, link, and shader conversion on-device.
- Restart after cancellation or low storage without corrupting an earlier successful install.
- Game launches and reaches gameplay; logs identify every failed stage.
- Repeat setup using the same inputs detects a valid existing install and avoids needless rebuilds.

Until then, keep the current pack-based path available and label on-device generation as unfinished.
