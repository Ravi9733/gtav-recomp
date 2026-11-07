#!/usr/bin/env python3
"""Patch Plume so Volk uses the app-selected Android Vulkan loader and reports real driver identity."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_plume_turnip.py /path/to/plume_vulkan.cpp")
source = Path(sys.argv[1])
if not source.is_file():
    raise SystemExit(f"Plume source not found: {source}")
text = source.read_text()

if "GTAV_ANDROID_TURNIP_VOLK_BRIDGE" not in text:
    namespace = "namespace plume {"
    if namespace not in text:
        raise SystemExit("Could not locate namespace plume")
    text = text.replace(namespace, namespace + """
#if defined(__ANDROID__)
extern "C" void* gtav_android_turnip_get_gipa();
#endif
""", 1)
    needle = "        VkResult res = volkInitialize();"
    if text.count(needle) != 1:
        raise SystemExit("Expected exactly one Volk initialization site; refusing unsafe patch")
    replacement = """#if defined(__ANDROID__)
        // GTAV_ANDROID_TURNIP_VOLK_BRIDGE: keep all Vulkan entry points on one loader.
        auto turnipGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(gtav_android_turnip_get_gipa());
        VkResult res = VK_SUCCESS;
        if (turnipGipa) volkInitializeCustom(turnipGipa);
        else res = volkInitialize();
#else
        VkResult res = volkInitialize();
#endif"""
    text = text.replace(needle, replacement, 1)

if "GTAV_ANDROID_DRIVER_REPORT_DECL" not in text:
    namespace = "namespace plume {"
    if namespace not in text:
        raise SystemExit("Could not locate namespace plume for driver report")
    text = text.replace(namespace, namespace + """
#if defined(__ANDROID__)
extern "C" void gtav_android_turnip_report_driver(const char*, const char*);
#endif
""", 1)

if "GTAV_ANDROID_DRIVER_PROPERTIES" not in text:
    section = text.find("// Fill device names.")
    if section < 0:
        raise SystemExit("Could not locate physical-device enumeration")
    needle = """                VkPhysicalDeviceProperties deviceProperties;
                vkGetPhysicalDeviceProperties(physicalDevices[i], &deviceProperties);"""
    at = text.find(needle, section)
    if at < 0:
        raise SystemExit("Could not locate physical-device properties query")
    replacement = needle + """
#if defined(__ANDROID__)
                // GTAV_ANDROID_DRIVER_PROPERTIES: report the identity returned by Vulkan itself.
                uint32_t extensionCount = 0;
                bool hasDriverProperties = deviceProperties.apiVersion >= VK_API_VERSION_1_2;
                if (vkEnumerateDeviceExtensionProperties(physicalDevices[i], nullptr, &extensionCount, nullptr) == VK_SUCCESS) {
                    std::vector<VkExtensionProperties> extensions(extensionCount);
                    if (vkEnumerateDeviceExtensionProperties(physicalDevices[i], nullptr, &extensionCount, extensions.data()) == VK_SUCCESS) {
                        for (const auto& extension : extensions)
                            if (std::string(extension.extensionName) == "VK_KHR_driver_properties") hasDriverProperties = true;
                    }
                }
                if (hasDriverProperties) {
                    VkPhysicalDeviceDriverProperties driver = {};
                    driver.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
                    VkPhysicalDeviceProperties2 properties = {};
                    properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                    properties.pNext = &driver;
                    vkGetPhysicalDeviceProperties2(physicalDevices[i], &properties);
                    fprintf(stderr, "[native] Vulkan driver: %s | %s\\n", driver.driverName, driver.driverInfo);
                    gtav_android_turnip_report_driver(driver.driverName, driver.driverInfo);
                }
#endif"""
    text = text[:at] + text[at:].replace(needle, replacement, 1)

# Resolve BDA through the selected device, not a process-global dispatch pointer.
if "GTAV_ANDROID_DEVICE_BDA_DISPATCH" not in text:
    start_marker = "    uint64_t VulkanBuffer::getDeviceAddress() const {"
    end_marker = "\n    // VulkanBufferFormattedView"
    start = text.find(start_marker)
    if start < 0 or text.find(start_marker, start + 1) >= 0:
        raise SystemExit("Could not locate one unique VulkanBuffer::getDeviceAddress")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit("Could not locate end of VulkanBuffer::getDeviceAddress")
    method = """    uint64_t VulkanBuffer::getDeviceAddress() const {
        // GTAV_ANDROID_DEVICE_BDA_DISPATCH
        if (device == nullptr || device->vk == VK_NULL_HANDLE || vk == VK_NULL_HANDLE ||
            !device->capabilities.bufferDeviceAddress) return 0;
        VkBufferDeviceAddressInfo info = {};
        info.sType = VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
        info.buffer = vk;
        auto getKHR = reinterpret_cast<PFN_vkGetBufferDeviceAddressKHR>(
            vkGetDeviceProcAddr(device->vk, "vkGetBufferDeviceAddressKHR"));
        if (getKHR) return getKHR(device->vk, &info);
        auto getCore = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
            vkGetDeviceProcAddr(device->vk, "vkGetBufferDeviceAddress"));
        return getCore ? getCore(device->vk, &info) : 0;
    }"""
    text = text[:start] + method + text[end:]

if "GTAV_ANDROID_VOLK_DEVICE_DISPATCH" not in text:
    needle = """        VkResult res = vkCreateDevice(physicalDevice, &createInfo, nullptr, &vk);
        if (res != VK_SUCCESS) {
            fprintf(stderr, "vkCreateDevice failed with error code 0x%X.\\n", res);
            return;
        }

        for (uint32_t i = 0; i < queueFamilyCount; i++) {"""
    replacement = """        VkResult res = vkCreateDevice(physicalDevice, &createInfo, nullptr, &vk);
        if (res != VK_SUCCESS) {
            fprintf(stderr, "vkCreateDevice failed with error code 0x%X.\\n", res);
            return;
        }

        // GTAV_ANDROID_VOLK_DEVICE_DISPATCH: populate device-level Volk function pointers.
        volkLoadDevice(vk);

        for (uint32_t i = 0; i < queueFamilyCount; i++) {"""
    if text.count(needle) != 1:
        raise SystemExit("Could not locate unique vkCreateDevice success block")
    text = text.replace(needle, replacement, 1)

required = ("volkInitializeCustom(turnipGipa)", "GTAV_ANDROID_DRIVER_PROPERTIES",
            "gtav_android_turnip_report_driver(driver.driverName", "GTAV_ANDROID_DEVICE_BDA_DISPATCH",
            "GTAV_ANDROID_VOLK_DEVICE_DISPATCH")
missing = [x for x in required if x not in text]
if missing:
    raise SystemExit("Turnip patch verification failed: " + ", ".join(missing))
text = text.replace("        volkLoadDevice(vk);", "        volkLoadDevice(vk);", 1)
source.write_text(text)
print("Applied and verified Android Turnip Volk bridge.")
