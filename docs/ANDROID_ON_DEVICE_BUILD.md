# Android on-device build: implementation status

This document tracks the separate Android path. The desktop build remains unchanged.

## Current behavior (verified in source)

- `tools/android/app/java/com/gtavrecomp/app/SetupActivity.java` asks the user to import
  `gtav-android-pack.zip` produced by **Build for Android on a PC**, plus Disc 1, Disc 2,
  and Title Update 27.
- The imported pack contains `libgtav_game.so`, `expected.txt`, and `pack.txt`.
- The setup activity imports that prebuilt library into app-private storage, then starts the game.
- `loader/src/install.cpp::RunAndroid()` is not an on-device entry point today. It calls
  `IsInstalled(home_)`, expects the PC-installed game layout and Android package files, and
  reads compiler and shader tools from `root_/tools`.
- `Codegen()` currently launches `root_/tools/game_dll/rexglue`.
- `Compile()` currently launches `root_/tools/llvm/bin/clang`.
- `LinkAndroid()` currently launches `root_/tools/llvm/bin/ld.lld`.
- `PhoneShaders()` currently launches host `XenosRecomp` and `dxc` binaries.
- The Android APK packager can optionally include Android ARM64 ReXGlue, clang, and LLD
  executables, but packaging them does not make the setup activity invoke them.

## Work already present on this branch

- Android ARM64 ReXGlue codegen build workflow and artifact.
- Android ARM64 clang/LLD build script and workflow.
- APK packaging flags `ON_DEVICE_CODEGEN=1` and `ON_DEVICE_TOOLS=1`.

These are toolchain groundwork only. They are not yet a working on-device build.

## Required implementation sequence

1. **Runtime tool discovery:** resolve the APK's extracted native-library directory and
   verify that packaged executables are present, executable, AArch64, and runnable on the
   supported Android API level. Do not execute from shared storage.
2. **On-device process runner:** provide a tested Android process-launch path for codegen,
   clang, LLD, XenosRecomp, and DXC, with private writable working directories, cancellation,
   output capture, and clear error reporting.
3. **Codegen integration:** make the installer invoke the packaged Android ReXGlue binary
   against the game executable and title update after the original data install completes.
4. **Compile/link integration:** point the Android compile/link stages at packaged clang/LLD,
   configure their Android sysroot and headers, and validate the resulting `libgtav_game.so`.
5. **Shader pipeline:** build and package Android ARM64 XenosRecomp and DXC (or implement a
   compatible in-process alternative), then run shader conversion on-device.
6. **Setup UI:** replace the PC-pack picker with a separate on-device build action and progress,
   while preserving the existing import-pack path as a fallback until the new path passes tests.
7. **End-to-end verification:** install from the user's own Disc 1, Disc 2, and TU27 on a clean
   device, build the game library locally, launch it, and verify logs and output hashes.

## Do not mark Android-only complete until

- no PC-generated game pack is required for the new setup path;
- all build tools are packaged for Android and launched from app-private/native-library paths;
- a clean-device end-to-end install, codegen, compile, link, shader conversion, and game launch
  have all succeeded.

The current APK and workflows must not be described as standalone/playable until those checks pass.
