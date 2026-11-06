# Android port: status (2026-10-30)

**Next session (owner):** bring the Mac session's arm64 performance work (origin commits, the
HANDOFF_MAC_* / ARM64_NOTES_FOR_ANDROID.md updates) into this Android build and make it play as
well as it can. Nothing here is committed yet: merge it with the Mac work first.

Owner play-test 10-30 night: "plays pretty well". Menu: RB + Back on a controller, or the
overlay's Menu button. Max performance keeps per-pixel sun tiles on (off = gray 16x16 blocks).

GTA V runs in-game on a Galaxy S23 FE (Snapdragon 8 Gen 1, Adreno 730, Android 16), landscape and
letterboxed, with on-screen touch controls (a Bluetooth pad hides them). About 10-15 fps while the
GPU is thermally throttled (350 of 818 MHz after long sessions on the charger).

## Perf session 10-30/31 (Mac arm64 work merged; measured on the phone, live A/B per change)

Branch `android` (not pushed). Numbers: same-run `tools/android/ab.sh NAME 'cvar=a|b'` A/Bs.
Cold the game reaches 28-29 fps in the open world; hot (battery 40-45 C on the charger, GPU
220-317 MHz, CPU clusters ~1.1-1.4 GHz) 14-17 fps: record 40-45 ms, GPU 38-42 ms per frame.

Kept:
- Leaving the app (Home, app switch, lock screen) crashed in vkCreateFramebuffer
  (VK_ERROR_SURFACE_LOST_KHR): the swap chain is rebuilt on SDL's new window (24 ms after resume).
- Main game thread pinned to the prime core (`gtav_android_main_prime`): +3-4%. It is busy ~80%
  of the frame (`--gtav_wait_profile`) and the scheduler had it on little cores 45% of the time.
- Fence wait on (as on macOS): neutral to +5%, prevents the open-world one-frame slabs.
- New packets start at the largest arena seen (no growth copies).
- args.txt `env:` lines are set before libmain.so loads (static initializers read some).
- Presets: Best image = game limiter 30 + vsync; Max performance = uncapped.
- appCategory=game in the manifest (Game Booster options for the app).

Measured and left off on Android (all reversible by cvar):
| change | result |
|---|---|
| SDK page-protection write watch | 2x slower (its global lock on every fault) |
| hash worker threads (macOS win) | not a win |
| in-pass (fetch) resolves | neutral (GTAV_NATIVE_FETCH_RESOLVE=1 opt-in) |
| draw clears off (vkCmdClearAttachments) | neutral |
| texture recheck 2 vs 8 frames | neutral |
| worker conversion of per-frame vertex data (`gtav_native_convert_threads`) | 1-6 ms slower recording |
| XMA decoder flush instead of reopen (`xma_flush_streams`) | ~20% less audio-thread CPU but 13-16 audio bursts vs 4 in the scripted desktop capture |

Profile (simpleperf, cold): render thread ~16% of all samples (XXH3 hashing 14%, vertex
conversion 12% of it), game D3D thread 12%, main thread 11%, audio 17% (a fifth of that was the
XMA codec reopen), command processor 8-9%. Per frame ~7-8 MB of fence-copied vertex data is
converted (snapshots), 1.7-2 MB of rect lists.

What 30 fps hot would take (not started): halve both CPU recording and GPU time. Candidates:
render resolution below 720p (shaders compute 16 px tile indices from FragCoord: needs a scale in
every recompiled shader), recording on several threads (secondary command buffers), fewer GMEM
loads per pass (LOAD_OP_CLEAR/DONT_CARE where a pass starts with a full clear), less charging heat
(Game Booster "Pause USB Power Delivery", or play unplugged).

## Release layout (11-01): the APK holds no game code

The owner's rule: nothing Rockstar-derived is published (Take-Two / re3). The developer APK
(`build_apk.sh`) has the recompiled game inside libmain.so (~300 MB) and must stay private.
The public one (`RELEASE=1 build_apk.sh`, packaged by `tools/android/make_shell.sh dist/android`):

| library | what | from |
|---|---|---|
| libmain.so (41 KB) | shim: SDL calls its SDL_main; it dlopens `<files>/libgtav_game.so` and calls SDL_main in libgtav_fast.so | src/android_main_shim.cpp |
| libgtav_fast.so | all our code (renderer, hooks, bridge), `GTAV_GAME_DLL=ON`; the 2931 game functions it calls stay undefined | build/android-dll |
| libgtav_setup.so | setup screen's data install (install.cpp RunDataInstall over JNI) | src/android_setup_jni.cpp |
| librexruntime.so, librexgpu-xenos.so, libc++_shared.so | SDK, as before | |
| libgtav_game.so | **not in the APK**: the player's game code, from their PC | Build for Android |

Binding: bionic resolves undefined symbols inside the tree one dlopen loads (game ->
libgtav_fast -> librexruntime -> libc), so the game finds the hooks/bridge and libgtav_fast finds
the game functions, both at load (`-z now`). The game library must be in private storage
(`getFilesDir()`): shared storage is noexec.

Player flow: PC launcher "Build for Android" / `--android` (install.cpp RunAndroid: codegen +
reference check, freestanding aarch64-linux-android30 compile with the bundled LLVM 19, lld
`-flavor gnu` against `android/link/`, Adreno shaders through adreno_shader_fix.cpp) ->
`android/gtav-android-pack.zip` (libgtav_game.so, spirv/, expected.txt, pack.txt with the hash of
the libgtav_fast.so it fits). On the phone SetupActivity takes the pack and the three disc files
(SAF; the installer reads `/proc/self/fd/N`), unpacks, installs `data/{game,update,user}` into the
external files dir, then starts GameActivity, which then passes those roots and the built-in
release flags.

Not done yet: a phone test of the whole flow; on-device codegen/compile (clang/lld/dxc for
Android: tools/android/build_llvm.sh exists, building downloaded LLVM needs the owner's OK).

## Build and run (from Linux, phone on adb)

```sh
tools/android/build_sdk.sh            # SDK for arm64-v8a (NDK r29, API 30, armv8.2-a+lse)
tools/android/build_game.sh           # libmain.so (codegen runs with the host rexglue)
tools/android/make_spirv.sh           # game SPIR-V for Adreno -> build/android/spirv (push it)
tools/android/build_apk.sh install    # debug APK without Gradle (SDLActivity + GameActivity)
tools/android/set_args.sh [--flag=v ...] [env:NAME=V]   # files/args.txt on the phone
tools/android/run.sh [secs]           # restart, wait, print crash + log tail
```
Data on the phone: `/sdcard/Android/data/com.gtavrecomp.app/files/` with `game/disc2_files`,
`game/update`, `userdata`, `spirv`, `args.txt`, `logs/gtav.log`, `logs/stderr.txt`. Files pushed
with adb belong to the shell user: `chmod -R a+rwX` them (the app sees its own folder directly).
Phone storage is case-insensitive, like the Xbox.

Debugging without root: crashes write a tombstone (`adb logcat -b crash`) and, for faults the
SDK's handlers see, a backtrace in `logs/stderr.txt`. `tools/android/debug_crash.sh` runs the game
under lldb (lldb-server via run-as); `simpleperf record --app com.gtavrecomp.app` profiles.

## Adreno 730 / driver 0615.103 facts (Vulkan 1.1, no shaderInt64)

- 64-bit shader integers: `tools/android/lower_int64.py` lowers them to uvec2 (game shaders in
  make_spirv.sh, the renderer's own shaders via lower_header.py). The compiler also asserts on
  64-bit shifts and 2-component device-address loads; the menu shader uses a vertex buffer.
- `vkWaitForFences(UINT64_MAX)` returns VK_TIMEOUT early: plume now waits in slices. Treating
  the timeout as "done" reused in-flight command buffers and looked like random GPU faults.
- Core `vkGetBufferDeviceAddress` is a null driver entry: plume prefers the KHR one.
- minImageCount is 3: 5 swap chain images, or acquire blocks with frames in flight.
- The rect-list geometry shader crashes the compiler: rect lists on the CPU (gtav_native_rect_gs).
- Failed pipelines: plume returns null now (it returned objects with null handles).

## SDK / SDL changes (rexglue-sdk-gtav-linux.patch, sdl3-gtav.patch, plume-gtav.patch)

Android platform bits (fibers without ucontext, memfd guest arena reserved with
MAP_FIXED_NOREPLACE, ANativeWindow surface, SDL_main, GPU plugin path), `window_presenter` cvar
(one surface per window), SEH/fault handlers chain instead of dying silently, SDL's Android event
wait no longer spins (poll sentinel woke it), controller pumping rate-limited.

## Performance notes (10-30 evening)

- Pixel shaders were ~60% of the GPU frame (flat-PS A/B: 44 -> 18 ms). Shader constants now come
  from dynamic uniform buffers (set 4; tools/android/ubo_constants.py + plume
  CONSTANT_BUFFER_DYNAMIC): GPU frame at full clock ~20-29 ms (was 43-46).
- CPU recording is now the limit (~25-36 ms): vertex streams (big-endian conversion, now with a
  bulk NEON path), texture/stream hashing (no write watch on kernel 5.10), bind+draw.
- libmain is a shared library: -Bsymbolic-functions / -fno-semantic-interposition (was 10% PLT).
- Audio folded to stereo (audioserver 40% -> 9% of a core). Renderer threads pinned to big cores.
- Heat: the phone throttles the GPU to 220-350 MHz within minutes on the charger.

## Next

1. (FIXED) Blocky 16x16 tile artifacts: the resolve-to-memory compute shader (native_memory.hlsl)
   did not compile on Adreno, so no resolve reached guest memory and the game built its
   lighting-tile lists from a stale table. It compiles once lowered (lower_header.py).
2. CPU recording: write watch via mprotect, or parallel stream conversion.
3. Intermittent guest null read (sub_833CB750, once) - watch for repeats.
4. Thermal: frame cap / vsync defaults for sustained play.

## 10-31: rendering fixes and render scale

- Render scale 0.75 by default on Android (gtav_native_render_scale): GPU -4..9 ms a frame when
  throttled; the G-buffer and shadow passes are geometry-bound and do not shrink.
- Grey/mosaic blocks on textures: new render targets and resolves start zeroed (Adreno hands out
  recycled memory); R16G16B16A16_UNORM (not sampleable on Adreno) textures stored as SNORM; point
  filtering for 32-bit float fetches (no linear filtering on Adreno).
- Light 16x16 squares in shade while moving: the tile classification variant (_tiles2) now keeps
  fully shadowed tiles in the per-pixel sun draw (they were drawn by neither sun variant). Found
  with frame dumps (mem_*.bin) and a flat-colour view of the sun draws. Tools:
  tools/native/count_squares.py, tools/android/walk_squares.sh --ab (live A/B in one walk).
- Final sustained run (cool start to 44 C battery, GPU 317-492 MHz): 24-36 fps, mostly 26-35.
- Measured off: load-op clears, record thread, coarse shading (available as cvars).


## Turnip integration audit (11-06)

The Android native renderer creates its Plume Vulkan interface in `Renderer::Init`, enumerates
physical-device names with `getDeviceNames()`, and selects the first name matching
`gtav_native_device` (Android default: `Adreno`). This is a physical-GPU preference only; it does
not select or load a Vulkan ICD/driver. The current Android APK does not bundle an in-app Turnip
loader, and the renderer does not report Vulkan `driverName` / `driverInfo` yet. The settings menu
now states this distinction so it cannot be mistaken for a working Turnip switch.

Do not add desktop-only `VK_ICD_FILENAMES` / `VK_DRIVER_FILES` environment-variable logic as a
shortcut on Android. The next implementation step is to integrate a supported Android Vulkan
loader/driver-loading mechanism (or explicitly support a verified system-installed driver), expose
the actual active Vulkan driver identity from the physical-device properties, and only then add a
Turnip selection/status control. Validate ABI, Android loader compatibility, and the Adreno 730
build before shipping; no driver archive is assumed compatible merely because it is arm64.

This audit is source-only. It does not alter game data, saves, the Android pack, or the APK.


## Turnip loader feasibility check (2026-11-06, follow-up)

A supported Android injection library has now been identified: `bylaws/libadrenotools`
(BSD-2-Clause). Its public API is `adrenotools_open_libvulkan(...,
ADRENOTOOLS_DRIVER_CUSTOM, ..., hookLibDir, customDriverDir, customDriverName, ...)`.
The upstream documentation imposes important constraints:

- Android 9+ and arm64 are supported; this project targets arm64-v8a and minSdk 30.
- `hookLibDir` must be exactly Android `ApplicationInfo.nativeLibraryDir`.
- The custom driver directory must be app-internal storage (not /sdcard or app external files).
- The APK must extract native libraries (`useLegacyPackaging=true` / `extractNativeLibs=true`) so the hook libraries exist in `nativeLibraryDir`.
- The custom driver must be staged under a safe internal path and its exact soname must be passed to the loader.
- A successful non-null loader handle is not enough: enumerate physical devices and inspect driver properties before reporting Turnip active.

The current APK manifest already sets `android:extractNativeLibs="true"`, and the APK packager places arm64 shared libraries under `lib/arm64-v8a`. Those are useful prerequisites, not proof that libadrenotools is integrated.

### Renderer integration blocker

`Renderer::Init` currently calls `plume::CreateVulkanInterface()` and then creates the device. Plume is built as a separate target from the external `thirdparty/plume` checkout and is linked into the game. Its Vulkan backend currently uses the Vulkan API entry points directly; the game does not pass an injected Vulkan loader handle into Plume.

The AdrenoToolsTest reference implementation demonstrates opening a custom Vulkan loader and resolving Vulkan entry points with `dlsym`. That pattern cannot simply be added beside the current renderer: it would only probe a second Vulkan instance while Plume continued using the system loader. The real integration must route Plume's complete Vulkan dispatch through the injected loader (e.g. a loader/dispatch-table integration based on `vkGetInstanceProcAddr`) before creating the first Vulkan instance. It must also retain a system-loader fallback if the custom driver fails.

### Safe implementation sequence

1. Vendor or reproducibly fetch a pinned, license-preserving libadrenotools revision and its linker-namespace subdependency; build the arm64 hook libraries with the NDK.
2. Package the hook libraries into the APK and confirm they are extracted to `nativeLibraryDir`.
3. Add a driver-import screen/setup flow that copies a user-supplied compatible driver archive into app-internal storage, validates the archive contents/ABI/sonames, and never executes from shared storage.
4. Add a single process-wide Vulkan loader abstraction. On Android, initialize it with libadrenotools before Plume creates an instance; on failure, explicitly fall back to the system Vulkan loader.
5. Route every Plume Vulkan function through that loader's dispatch table; do not mix system-loader and injected-loader handles.
6. Report the actual physical device and `VkPhysicalDeviceDriverProperties` (`driverName`, `driverInfo`, conformance version where available) in logs and UI. The UI must say Turnip only when those properties confirm the loaded custom driver.
7. Test system fallback first, then a known-compatible Adreno 730 Turnip package on a Snapdragon 8 Gen 1/8+ Gen 1 device. Check startup, swapchain, shader/pipeline creation, device loss, resume-from-background, and long-session stability before shipping.

No driver archive has been assumed compatible with Adreno 730. No custom driver is currently loaded by the game; this section records verified implementation requirements, not a completed integration.

### Volk custom-loader integration point (2026-11-06)

A source-level check of the exact upstream Plume Vulkan backend identified a narrower integration point than a full Vulkan dispatch rewrite: Plume includes Volk (volk.h) and its VulkanInterface constructor currently calls volkInitialize() immediately before vkCreateInstance, followed by volkLoadInstance(instance). Volk exposes volkInitializeCustom(PFN_vkGetInstanceProcAddr), so the Android custom loader can be injected before the first Plume interface is constructed.

Implementation direction:

- Add a Plume-side setter (in the maintained plume-gtav.patch) for a process-wide PFN_vkGetInstanceProcAddr; on Android call volkInitializeCustom() with it, otherwise preserve the existing volkInitialize() path.
- In the Android renderer startup, open libadrenotools first, resolve vkGetInstanceProcAddr from its returned loader handle, and set the Plume hook before CreateVulkanInterface().
- Keep the returned loader handle alive for the process lifetime. If opening the custom loader or resolving its entry point fails, select the normal volkInitialize() system-loader path before Plume initialization; do not switch loaders after Vulkan objects exist.
- After instance/device enumeration, collect VkPhysicalDeviceDriverProperties through Plume and surface the confirmed driver identity. The UI must distinguish requested driver from active driver.

This is a viable concrete injection seam because Volk already centralizes Vulkan entry-point loading. It still requires the pinned libadrenotools + linker-namespace build, Android JNI/private-storage driver import, a maintained Plume patch, APK packaging changes, and an arm64 build/device test. None of those are represented as complete by this note.


## Turnip implementation work started (2026-11-06)

The first reproducible build/package slice is now committed on `android-turnip-ios-ui`:

- `tools/android/build_turnip_loader.sh` checks out the pinned `bylaws/libadrenotools` revision
  `8fae8ce254dfc1344527e05301e43f37dea2df80`, initializes its `liblinkernsbypass` submodule,
  builds arm64-v8a for Android API 30, and verifies the five expected shared libraries.
- `tools/android/build_apk.sh` accepts `TURNIP=1` and packages those libraries into
  `lib/arm64-v8a`. The default APK path is unchanged when `TURNIP` is unset.

Build sequence on the Android/Linux build host:

```sh
tools/android/build_turnip_loader.sh
TURNIP=1 tools/android/build_apk.sh
```

Important boundary: this packages the loader runtime; it does **not** yet activate Turnip in the
renderer. The next code step is to obtain the Android app's private files path and
`ApplicationInfo.nativeLibraryDir`, stage and validate a user-imported driver in app-internal
storage, call `adrenotools_open_libvulkan`, and pass the resolved
`PFN_vkGetInstanceProcAddr` into Plume's Volk initialization before its first Vulkan instance.
Until that hook and driver-property verification land, the UI must not claim Turnip is active.

The iOS-style menu work remains on the same branch. APK packaging remains opt-in for the new
loader libraries, and this change does not touch the installed game pack, save files, or user data.


## Turnip integration implementation pass (2026-11-06)

The earlier feasibility audit above described the pre-integration state. The branch now contains a source-level Android loader path:

- `gtav/src/native/turnip_loader.cpp` dynamically opens the packaged `libadrenotools.so`, calls `adrenotools_open_libvulkan` with the custom-driver feature, and returns the injected loader's `vkGetInstanceProcAddr`. It keeps both loader handles alive for the process.
- `tools/android/patch_plume_turnip.py` is run by the Android CMake configure before Plume is added. It makes Plume call `volkInitializeCustom` with that GIPA when Turnip is requested; otherwise it retains the normal `volkInitialize` system-loader path. It refuses to patch if the expected single initialization site is not present.
- `GameActivity` passes the app-private driver directory, APK `nativeLibraryDir`, and selected driver soname to native code before SDL loads the game library.
- `DriverSettingsActivity` is reachable from the launcher app shortcut (long-press the icon). It imports ARM64 `.so` libraries from a user-selected ZIP into `getFilesDir()/turnip/`, validates ELF class/endianness/AArch64 machine type, filters non-ARM64 ABI paths, limits extracted size, and defaults Turnip to OFF.
- The native Control Center distinguishes a Turnip request from proof of the active driver. Startup loader messages use the Android log tag `GtavTurnip`.
- `tools/android/build_turnip_loader.sh` is now usable in CI without the developer's local `~/android-tools/env.sh`; a workflow builds the pinned AdrenoTools libraries and checks Java/native syntax plus ARM64 ELF output.

Build path on a configured Android build host:

```sh
tools/android/build_turnip_loader.sh
TURNIP=1 tools/android/build_apk.sh
```

This is implementation source, not a verified release. The workflow/device build has not yet been confirmed here. The remaining critical verification is to build the APK, test system-Vulkan fallback, test a compatible Adreno 730 Turnip archive, and query `VkPhysicalDeviceDriverProperties` (driverName/driverInfo) after Plume enumerates the physical device. Do not label Turnip active until that property check succeeds. The new code does not modify the installed game pack, game data, or saves.


### Driver identity reporting added

The Plume patch step now also queries `VkPhysicalDeviceDriverProperties` where the device exposes Vulkan 1.2 or `VK_KHR_driver_properties`. It logs the driver's reported `driverName` and `driverInfo`, and forwards those exact strings to the Android Control Center. The UI labels Turnip as verified only when the Vulkan-reported driver name contains "Turnip"; otherwise it shows the reported driver name without claiming Turnip is active. Runtime/device confirmation is still pending.
