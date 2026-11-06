#!/usr/bin/env python3
"""Apply the Android Turnip Volk entry-point bridge to the external Plume checkout.

The project carries plume-gtav.patch for its other renderer changes. This small, idempotent
step runs after that patch is applied and before Plume is configured by CMake.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_plume_turnip.py /path/to/thirdparty/plume/plume_vulkan.cpp")

source = Path(sys.argv[1])
if not source.is_file():
    raise SystemExit(f"Plume source not found: {source}")

text = source.read_text()
marker = "// GTAV_ANDROID_TURNIP_VOLK_BRIDGE"
if marker in text:
    print("Plume Turnip bridge already applied.")
    raise SystemExit(0)

namespace = "namespace plume {"
if namespace not in text:
    raise SystemExit("Could not locate namespace plume in plume_vulkan.cpp")
text = text.replace(namespace, namespace + """
#if defined(__ANDROID__)
extern "C" void* gtav_android_turnip_get_gipa();
#endif
""", 1)

needle = "        VkResult res = volkInitialize();"
if text.count(needle) != 1:
    raise SystemExit("Expected exactly one Volk initialization site; refusing an unsafe patch.")
replacement = """#if defined(__ANDROID__)
        // GTAV_ANDROID_TURNIP_VOLK_BRIDGE: use the same injected loader for every Vulkan call.
        auto turnipGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            gtav_android_turnip_get_gipa());
        VkResult res = turnipGipa ? volkInitializeCustom(turnipGipa) : volkInitialize();
#else
        VkResult res = volkInitialize();
#endif"""
text = text.replace(needle, replacement, 1)
source.write_text(text)
print("Applied Android Turnip Volk bridge to Plume.")
