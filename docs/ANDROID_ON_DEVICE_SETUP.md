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

**Initial implementation committed, device test still required.** `install.cpp` now keeps
`LOADER_NO_SPAWN` for iOS only and uses an Android-specific `fork` + `execve` path. It builds an
argument vector before forking, redirects stdout/stderr to the stage log, changes to the requested
working directory, executes the absolute tool path, and uses the existing wait/cancel handling.
The Android path avoids bionic's API-34-only `posix_spawn_file_actions_addchdir_np`.

Before relying on it, build the Android setup library and test on API 30+ that an app-private
executable can start, its log is captured, nonzero exit codes are reported, and cancellation kills
and reaps the child. The process runner alone does not provide the missing Android tools.

### 2. Android code generator

Build the exact ReXGlue/Xenos recompilation generator for `arm64-v8a` and Android API 30.
Verify all runtime dependencies and generated output against the desktop generator on the same
user-supplied TU27 installation. The generated source set is large (hundreds of C++ files), so
memory use, temporary storage, and resumability must be measured on real devices.


### 2a. Codegen target constraint found during source audit

The current `gtav/CMakeLists.txt` explicitly treats Android as a cross-build: when
`ANDROID AND TARGET rex::rexglue`, it points the imported `rex::rexglue` executable at
`../rexglue-sdk/out/install/linux-amd64/bin/rexglue`. That is a Linux host tool used while
building Android on a computer; it is not an Android executable and cannot be launched by the
phone's setup process. The SDK source/build tree is not vendored in this repository (only patch
files are present), so an Android-native generator cannot be produced from this checkout alone.

Do not simply change the imported executable path to an Android path: the SDK's codegen target
and its dependencies must first be audited and cross-built for arm64-v8a/API 30. If codegen has
host-only dependencies, the viable alternative is to ship generated sources or move generation
to a separate host-assisted step—but that would not meet the requested PC-free setup. The
acceptance test is to run the generator binary on-device against the user's TU27 inputs and
compare its output hashes with the desktop reference manifest.

### 2b. SDK Android SDL backend correction

The pinned ReXGlue SDK's vendored dependency configuration uses a broad
`if(UNIX AND NOT APPLE)` condition to enable desktop X11, Wayland, ALSA, PulseAudio,
and PipeWire SDL drivers. Android also satisfies `UNIX` in the NDK toolchain, so that
condition would incorrectly request desktop Linux backends during an Android SDK build.
The Linux SDK patch now narrows that block to `UNIX AND NOT APPLE AND NOT ANDROID`,
leaving SDL to select its native Android backends. This is a source-level build fix only;
the SDK has not yet been cross-built with this patch, and it does not establish that the
full `rexglue` CLI and all its runtime dependencies work on-device.

### 3. Android compiler and linker

**APK packaging hook committed; runtime integration and device test still required.** Set
`ON_DEVICE_TOOLS=1` when running `tools/android/build_apk.sh` after
`tools/android/build_llvm.sh`. The script now requires the generated Android `clang` and `lld`
executables, places them in the APK's `lib/arm64-v8a` directory as `libclang.so` and
`libld.lld.so`, and marks them executable. Android extracts native libraries into the app's
private native-library directory, the only suitable location for executing app-bundled tools.

This does not yet prove that the executables start on API 30 or can compile/link the game. Next,
add a smoke test that invokes these exact paths through the Android process runner, verify the
sysroot/link inputs, and compile/link a tiny arm64 program before attempting the full generated
game. Then use the exact freestanding flags and link inputs from desktop `RunAndroidBuild`.

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
