#!/usr/bin/env python3
"""Apply Android Turnip Volk dispatch and driver-identity logging to external Plume."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_plume_turnip.py /path/to/thirdparty/plume/plume_vulkan.cpp")

source = Path(sys.argv[1])
if not source.is_file():
    raise SystemExit(f"Plume source not found: {source}")

text = source.read_text()

# Inject the app-owned libadrenotools GIPA before Plume initializes Volk. If Turnip is not
# requested or the custom loader fails, Volk keeps its normal Android system-loader behavior.
volk_marker = "// GTAV_ANDROID_TURNIP_VOLK_BRIDGE"
if volk_marker not in text:
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
        // GTAV_ANDROID_TURNIP_VOLK_BRIDGE: use one injected loader for every Vulkan call.
        auto turnipGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            gtav_android_turnip_get_gipa());
        VkResult res = VK_SUCCESS;
        if (turnipGipa) {
            volkInitializeCustom(turnipGipa);
        } else {
            res = volkInitialize();
        }
#else
        VkResult res = volkInitialize();
#endif"""
    text = text.replace(needle, replacement, 1)

# The game menu receives only the identity reported by Vulkan itself.
report_decl_marker = "// GTAV_ANDROID_DRIVER_REPORT_DECL"
if report_decl_marker not in text:
    namespace = "namespace plume {"
    if namespace not in text:
        raise SystemExit("Could not locate namespace plume for driver-report callback.")
    text = text.replace(namespace, namespace + """
#if defined(__ANDROID__)
// GTAV_ANDROID_DRIVER_REPORT_DECL
extern "C" void gtav_android_turnip_report_driver(const char*, const char*);
#endif
""", 1)

# Query the actual Vulkan driver reported by each physical device. Do not infer Turnip from
# the requested setting or from a successful adrenotools dlopen.
driver_marker = "// GTAV_ANDROID_DRIVER_PROPERTIES"
if driver_marker not in text:
    section = text.find("// Fill device names.")
    if section < 0:
        raise SystemExit("Could not locate Plume physical-device enumeration for driver reporting.")
    needle = """                VkPhysicalDeviceProperties deviceProperties;
                vkGetPhysicalDeviceProperties(physicalDevices[i], &deviceProperties);"""
    at = text.find(needle, section)
    if at < 0:
        raise SystemExit("Could not locate physical-device property query; refusing an unsafe patch.")
    replacement = needle + """
#if defined(__ANDROID__)
                // GTAV_ANDROID_DRIVER_PROPERTIES: report the driver selected by the Vulkan loader.
                uint32_t driverExtensionCount = 0;
                bool hasDriverProperties = deviceProperties.apiVersion >= VK_API_VERSION_1_2;
                if (vkEnumerateDeviceExtensionProperties(physicalDevices[i], nullptr,
                                                         &driverExtensionCount, nullptr) == VK_SUCCESS) {
                    std::vector<VkExtensionProperties> driverExtensions(driverExtensionCount);
                    if (vkEnumerateDeviceExtensionProperties(physicalDevices[i], nullptr,
                                                             &driverExtensionCount,
                                                             driverExtensions.data()) == VK_SUCCESS) {
                        for (const auto& extension : driverExtensions) {
                            if (std::string(extension.extensionName) == "VK_KHR_driver_properties")
                                hasDriverProperties = true;
                        }
                    }
                }
                if (hasDriverProperties) {
                    VkPhysicalDeviceDriverProperties driverProperties = {};
                    driverProperties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
                    VkPhysicalDeviceProperties2 properties2 = {};
                    properties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                    properties2.pNext = &driverProperties;
                    vkGetPhysicalDeviceProperties2(physicalDevices[i], &properties2);
                    fprintf(stderr, "[native] Vulkan driver: %s | %s (vendor 0x%04X, device 0x%04X)\\n",
                            driverProperties.driverName, driverProperties.driverInfo,
                            properties2.properties.vendorID, properties2.properties.deviceID);
                    gtav_android_turnip_report_driver(driverProperties.driverName,
                                                     driverProperties.driverInfo);
                } else {
                    fprintf(stderr, "[native] Vulkan driver properties extension unavailable for %s.\\n",
                            deviceProperties.deviceName);
                }
#endif"""
    text = text[:at] + text[at:].replace(needle, replacement, 1)

# Fail closed if a future upstream Plume change or edit drops either integration seam.
required = (
    "volkInitializeCustom(turnipGipa);",
    "GTAV_ANDROID_DRIVER_PROPERTIES",
    "gtav_android_turnip_report_driver(driverProperties.driverName",
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("Turnip Plume patch verification failed: " + ", ".join(missing))

source.write_text(text)
print("Applied and verified Android Turnip Volk bridge and Vulkan driver-identity logging to Plume.")
