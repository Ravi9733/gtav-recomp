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

# Renderer counters are declared extern by plume-gtav.patch and defined in
# tools/android/plume_export.cpp, which is compiled directly into libgtav_fast.so.

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

# Keep the deferred renderer interface compatible with the GTAV Vulkan backend.
# The Android source bundle may contain an older Plume interface header.
interface = source.with_name("plume_render_interface.h")
if not interface.is_file():
    raise SystemExit(f"Plume render interface not found: {interface}")
header = interface.read_text()

def add_virtual_after(anchor: str, declaration: str, label: str) -> None:
    global header
    if label in header:
        return
    if anchor not in header:
        raise SystemExit(f"Cannot locate Plume interface insertion point for {label}")
    header = header.replace(anchor, anchor + "\n        " + declaration, 1)

add_virtual_after(
    "virtual void setGraphicsDescriptorSet(RenderDescriptorSet *descriptorSet, uint32_t setIndex) = 0;",
    "virtual void setGraphicsDescriptorSetDynamic(RenderDescriptorSet *descriptorSet, uint32_t setIndex, const uint32_t *dynamicOffsets, uint32_t dynamicOffsetCount) {}",
    "setGraphicsDescriptorSetDynamic")
add_virtual_after(
    "virtual void writeTimestamp(const RenderQueryPool *queryPool, uint32_t queryIndex) = 0;",
    "virtual void beginOcclusionQuery(const RenderQueryPool *queryPool, uint32_t queryIndex) {}\n        virtual void endOcclusionQuery(const RenderQueryPool *queryPool, uint32_t queryIndex) {}\n        virtual void setFragmentShadingRate(uint32_t width, uint32_t height) {}",
    "beginOcclusionQuery")
add_virtual_after(
    "virtual void setGraphicsDescriptorSet(RenderDescriptorSet *descriptorSet, uint32_t setIndex) = 0;",
    "virtual void setGraphicsRootDescriptor(RenderBufferReference bufferReference, uint32_t rootDescriptorIndex) {}",
    "setGraphicsRootDescriptor")
interface.write_text(header)

# Resolve buffer-device-address through the selected VkDevice instead of Volk's
# process-global device trampoline. Patch only the uniquely delimited method body: upstream
# and the maintained plume-gtav.patch have changed its internal spelling/whitespace over time.
bda_marker = "GTAV_ANDROID_DEVICE_BDA_DISPATCH"
if bda_marker not in text:
    start_marker = "    uint64_t VulkanBuffer::getDeviceAddress() const {"
    end_marker = "\n    // VulkanBufferFormattedView"
    start = text.find(start_marker)
    if start < 0 or text.find(start_marker, start + 1) >= 0:
        raise SystemExit("Could not locate one unique VulkanBuffer::getDeviceAddress method; refusing an unsafe patch.")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit("Could not locate the end of VulkanBuffer::getDeviceAddress; refusing an unsafe patch.")
    method = """    uint64_t VulkanBuffer::getDeviceAddress() const {
        // GTAV_ANDROID_DEVICE_BDA_DISPATCH: use this VkDevice's driver entry point,
        // not Volk's process-global device trampoline (which can have a null driver target).
        if (device == nullptr || device->vk == VK_NULL_HANDLE || vk == VK_NULL_HANDLE ||
            !device->capabilities.bufferDeviceAddress) {
            return 0;
        }
        VkBufferDeviceAddressInfo info = {};
        info.sType = VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
        info.buffer = vk;

        auto getAddressKHR = reinterpret_cast<PFN_vkGetBufferDeviceAddressKHR>(
            vkGetDeviceProcAddr(device->vk, "vkGetBufferDeviceAddressKHR"));
        if (getAddressKHR != nullptr) {
            return getAddressKHR(device->vk, &info);
        }

        auto getAddressCore = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
            vkGetDeviceProcAddr(device->vk, "vkGetBufferDeviceAddress"));
        if (getAddressCore != nullptr) {
            return getAddressCore(device->vk, &info);
        }

        static bool reported = false;
        if (!reported) {
            reported = true;
            fprintf(stderr, "[native] buffer-device-address entry point unavailable on selected Vulkan device\\n");
        }
        return 0;
    }"""
    text = text[:start] + method + text[end:]

# Volk has separate instance- and device-level dispatch tables. The Turnip GIPA is
# installed before instance creation above; load the selected VkDevice immediately after
# vkCreateDevice so every device command (descriptor updates, buffer addresses, queues, etc.)
# is resolved through that same custom driver rather than an uninitialized/null dispatch slot.
volk_device_marker = "GTAV_ANDROID_VOLK_DEVICE_DISPATCH"
if volk_device_marker not in text:
    device_create = """        VkResult res = vkCreateDevice(physicalDevice, &createInfo, nullptr, &vk);
        if (res != VK_SUCCESS) {
            fprintf(stderr, "vkCreateDevice failed with error code 0x%X.\\n", res);
            return;
        }

        for (uint32_t i = 0; i < queueFamilyCount; i++) {"""
    device_create_patched = """        VkResult res = vkCreateDevice(physicalDevice, &createInfo, nullptr, &vk);
        if (res != VK_SUCCESS) {
            fprintf(stderr, "vkCreateDevice failed with error code 0x%X.\\n", res);
            return;
        }

        // GTAV_ANDROID_VOLK_DEVICE_DISPATCH: required after vkCreateDevice for the custom Turnip GIPA.
        volkLoadDevice(vk);

        for (uint32_t i = 0; i < queueFamilyCount; i++) {"""
    if text.count(device_create) != 1:
        raise SystemExit("Could not locate the unique Plume vkCreateDevice success block; refusing an unsafe patch.")
    text = text.replace(device_create, device_create_patched, 1)


# Never expose a partially constructed descriptor set to the renderer. Plume's
# VulkanDescriptorSet constructor can return early after descriptor-pool or set-layout
# allocation fails, while createDescriptorSet() historically returned the wrapper anyway.
# The next setBuffer() then forwarded a null VkDescriptorSet to vkUpdateDescriptorSets,
# which has been observed to SIGSEGV inside the Android Adreno driver.
descriptor_create_marker = "GTAV_ANDROID_DESCRIPTOR_SET_VALIDATION"
if descriptor_create_marker not in text:
    ctor_old = """        setLayout = new VulkanDescriptorSetLayout(device, desc);

        descriptorPool = createDescriptorPool(device, typeCounts, desc.lastRangeIsBoundless);
        if (descriptorPool == VK_NULL_HANDLE) {
            return;
        }"""
    ctor_new = """        setLayout = new VulkanDescriptorSetLayout(device, desc);
        if (setLayout == nullptr || setLayout->vk == VK_NULL_HANDLE) {
            fprintf(stderr, "[native] descriptor-set creation failed: descriptor-set layout is null\\n");
            return;
        }

        descriptorPool = createDescriptorPool(device, typeCounts, desc.lastRangeIsBoundless);
        if (descriptorPool == VK_NULL_HANDLE) {
            fprintf(stderr, "[native] descriptor-set creation failed: descriptor pool is null\\n");
            return;
        }"""
    if text.count(ctor_old) != 1:
        raise SystemExit("Could not locate unique VulkanDescriptorSet constructor allocation block.")
    text = text.replace(ctor_old, ctor_new, 1)

    ctor_alloc_old = """        VkResult res = vkAllocateDescriptorSets(device->vk, &allocateInfo, &vk);
        if (res != VK_SUCCESS) {
            fprintf(stderr, "vkAllocateDescriptorSets failed with error code 0x%X.\\n", res);
            return;
        }"""
    ctor_alloc_new = """        VkResult res = vkAllocateDescriptorSets(device->vk, &allocateInfo, &vk);
        if (res != VK_SUCCESS || vk == VK_NULL_HANDLE) {
            fprintf(stderr, "[native] vkAllocateDescriptorSets failed: VkResult=0x%X set=%p\\n",
                    res, reinterpret_cast<void*>(vk));
            vk = VK_NULL_HANDLE;
            return;
        }"""
    if text.count(ctor_alloc_old) != 1:
        raise SystemExit("Could not locate unique vkAllocateDescriptorSets failure block.")
    text = text.replace(ctor_alloc_old, ctor_alloc_new, 1)

    create_old = """    std::unique_ptr<RenderDescriptorSet> VulkanDevice::createDescriptorSet(const RenderDescriptorSetDesc &desc) {
        return std::make_unique<VulkanDescriptorSet>(this, desc);
    }"""
    create_new = """    std::unique_ptr<RenderDescriptorSet> VulkanDevice::createDescriptorSet(const RenderDescriptorSetDesc &desc) {
        // GTAV_ANDROID_DESCRIPTOR_SET_VALIDATION: do not return a wrapper whose Vulkan allocation failed.
        auto descriptorSet = std::make_unique<VulkanDescriptorSet>(this, desc);
        if (!descriptorSet || descriptorSet->vk == VK_NULL_HANDLE ||
            descriptorSet->descriptorPool == VK_NULL_HANDLE || descriptorSet->setLayout == nullptr ||
            descriptorSet->setLayout->vk == VK_NULL_HANDLE) {
            fprintf(stderr, "[native] refusing incomplete Vulkan descriptor set: device=%p set=%p pool=%p layout=%p\\n",
                    reinterpret_cast<void*>(vk), descriptorSet ? reinterpret_cast<void*>(descriptorSet->vk) : nullptr,
                    descriptorSet ? reinterpret_cast<void*>(descriptorSet->descriptorPool) : nullptr,
                    descriptorSet && descriptorSet->setLayout ? reinterpret_cast<void*>(descriptorSet->setLayout->vk) : nullptr);
            return nullptr;
        }
        return descriptorSet;
    }"""
    if text.count(create_old) != 1:
        raise SystemExit("Could not locate unique VulkanDevice::createDescriptorSet implementation.")
    text = text.replace(create_old, create_new, 1)

    buffer_old = """        const VulkanBuffer *interfaceBuffer = static_cast<const VulkanBuffer *>(buffer);
        const VkBufferView *bufferView = nullptr;"""
    buffer_new = """        const VulkanBuffer *interfaceBuffer = static_cast<const VulkanBuffer *>(buffer);
#if defined(__ANDROID__)
        // GTAV_ANDROID_DESCRIPTOR_BUFFER_VALIDATION: reject stale, foreign-device, or undersized buffers.
        if (device == nullptr || device->vk == VK_NULL_HANDLE || interfaceBuffer->device != device ||
            interfaceBuffer->vk == VK_NULL_HANDLE || bufferSize > interfaceBuffer->desc.size) {
            fprintf(stderr, "[native] refusing invalid Vulkan descriptor buffer: device=%p bufferDevice=%p buffer=%p size=%llu capacity=%llu\\n",
                    device ? reinterpret_cast<void*>(device->vk) : nullptr,
                    interfaceBuffer->device ? reinterpret_cast<void*>(interfaceBuffer->device->vk) : nullptr,
                    reinterpret_cast<void*>(interfaceBuffer->vk),
                    static_cast<unsigned long long>(bufferSize),
                    static_cast<unsigned long long>(interfaceBuffer->desc.size));
            return;
        }
#endif
        const VkBufferView *bufferView = nullptr;"""
    if text.count(buffer_old) != 1:
        raise SystemExit("Could not locate unique VulkanDescriptorSet::setBuffer handle block.")
    text = text.replace(buffer_old, buffer_new, 1)

    set_desc_old = """    void VulkanDescriptorSet::setDescriptor(uint32_t descriptorIndex, const VkDescriptorBufferInfo *bufferInfo, const VkDescriptorImageInfo *imageInfo, const VkBufferView *texelBufferView, void *pNext) {
        assert(descriptorIndex < setLayout->descriptorBindingIndices.size());"""
    set_desc_new = """    void VulkanDescriptorSet::setDescriptor(uint32_t descriptorIndex, const VkDescriptorBufferInfo *bufferInfo, const VkDescriptorImageInfo *imageInfo, const VkBufferView *texelBufferView, void *pNext) {
#if defined(__ANDROID__)
        if (device == nullptr || device->vk == VK_NULL_HANDLE || vk == VK_NULL_HANDLE ||
            descriptorPool == VK_NULL_HANDLE || setLayout == nullptr || setLayout->vk == VK_NULL_HANDLE) {
            fprintf(stderr, "[native] skipped Vulkan descriptor update: incomplete descriptor set (set=%p pool=%p layout=%p)\\n",
                    reinterpret_cast<void*>(vk), reinterpret_cast<void*>(descriptorPool),
                    setLayout ? reinterpret_cast<void*>(setLayout->vk) : nullptr);
            return;
        }
        if (descriptorIndex >= setLayout->descriptorBindingIndices.size() ||
            descriptorIndex >= setLayout->descriptorIndexBases.size()) {
            fprintf(stderr, "[native] skipped Vulkan descriptor update: descriptor index %u out of bounds\\n", descriptorIndex);
            return;
        }
#endif
        assert(descriptorIndex < setLayout->descriptorBindingIndices.size());"""
    if text.count(set_desc_old) != 1:
        raise SystemExit("Could not locate unique VulkanDescriptorSet::setDescriptor entry.")
    text = text.replace(set_desc_old, set_desc_new, 1)

# Fail closed if a future upstream Plume change or edit drops either integration seam.
required = (
    "volkInitializeCustom(turnipGipa);",
    "GTAV_ANDROID_DRIVER_PROPERTIES",
    "gtav_android_turnip_report_driver(driverProperties.driverName",
    "GTAV_ANDROID_DEVICE_BDA_DISPATCH",
    "GTAV_ANDROID_VOLK_DEVICE_DISPATCH",
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("Turnip Plume patch verification failed: " + ", ".join(missing))

source.write_text(text)
print("Applied and verified Android Turnip Volk bridge and Vulkan driver-identity logging to Plume.")
