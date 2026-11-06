#!/usr/bin/env python3
"""Idempotently enforce the Android Plume API additions before compiling GTAV."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLUME = ROOT / "thirdparty" / "plume"


def add_after(path, anchor, addition, marker):
    source = path.read_text()
    if marker in source:
        return
    count = source.count(anchor)
    if count != 1:
        raise SystemExit(f"{path.name}: expected one anchor for {marker}, found {count}")
    path.write_text(source.replace(anchor, anchor + addition, 1))


types = PLUME / "plume_render_interface_types.h"
builders = PLUME / "plume_render_interface_builders.h"
interface = PLUME / "plume_render_interface.h"

add_after(types, "        BC7_UNORM_SRGB,\n",
          "        B5G6R5_UNORM,  // 16-bit opaque DXT1 target format.\n",
          "B5G6R5_UNORM")
add_after(types, "        ACCELERATION_STRUCTURE\n",
          "        ACCELERATION_STRUCTURE,\n"
          "        INPUT_ATTACHMENT,\n"
          "        CONSTANT_BUFFER_DYNAMIC\n",
          "CONSTANT_BUFFER_DYNAMIC")
add_after(types, "            COPY = 1U << 2,\n",
          "            FRAGMENT = 1U << 3,\n",
          "FRAGMENT = 1U << 3")
add_after(types, "        case RenderFormat::R16_FLOAT:\n",
          "        case RenderFormat::B5G6R5_UNORM:\n",
          "case RenderFormat::B5G6R5_UNORM")
# The format is a colour format, so it must be included in both byte-size/format classification switches.
# If the first replacement inserted the marker globally, insert into the second switch by checking count.
source = types.read_text()
if source.count("case RenderFormat::B5G6R5_UNORM:") < 2:
    anchor = "        case RenderFormat::R16_FLOAT:\n"
    if source.count(anchor) != 1:
        raise SystemExit("Could not locate second RenderFormat classification switch")
    types.write_text(source.replace(anchor, anchor + "        case RenderFormat::B5G6R5_UNORM:\n", 1))

add_after(builders, "        uint32_t addConstantBuffer(uint32_t binding, uint32_t count = 1) {\n"
          "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::CONSTANT_BUFFER, binding, count, nullptr));\n"
          "        }\n",
          "\n        uint32_t addConstantBufferDynamic(uint32_t binding, uint32_t count = 1) {\n"
          "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::CONSTANT_BUFFER_DYNAMIC, binding, count, nullptr));\n"
          "        }\n",
          "addConstantBufferDynamic")
add_after(builders, "        uint32_t addReadWriteTexture(uint32_t binding, uint32_t count = 1) {\n"
          "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::READ_WRITE_TEXTURE, binding, count, nullptr));\n"
          "        }\n",
          "\n        uint32_t addInputAttachment(uint32_t binding, uint32_t count = 1) {\n"
          "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::INPUT_ATTACHMENT, binding, count, nullptr));\n"
          "        }\n",
          "addInputAttachment")

add_after(interface, "        virtual void setGraphicsDescriptorSet(RenderDescriptorSet *descriptorSet, uint32_t setIndex) = 0;\n",
          "        virtual void setGraphicsDescriptorSetDynamic(RenderDescriptorSet *descriptorSet, uint32_t setIndex, const uint32_t *dynamicOffsets, uint32_t dynamicOffsetCount) {}\n",
          "setGraphicsDescriptorSetDynamic")
add_after(interface, "        virtual void writeTimestamp(const RenderQueryPool *queryPool, uint32_t queryIndex) = 0;\n",
          "        virtual void beginOcclusionQuery(const RenderQueryPool *queryPool, uint32_t queryIndex) {}\n"
          "        virtual void endOcclusionQuery(const RenderQueryPool *queryPool, uint32_t queryIndex) {}\n",
          "beginOcclusionQuery")
add_after(interface, "        virtual std::unique_ptr<RenderQueryPool> createQueryPool(uint32_t queryCount) = 0;\n",
          "        virtual std::unique_ptr<RenderQueryPool> createOcclusionQueryPool(uint32_t queryCount) { return nullptr; }\n",
          "createOcclusionQueryPool")
add_after(interface, "        bool depthAttachmentReadOnly = false;\n",
          "        bool colorDiscard = false;\n",
          "colorDiscard")

required = {
    types: ["B5G6R5_UNORM", "INPUT_ATTACHMENT", "CONSTANT_BUFFER_DYNAMIC", "FRAGMENT = 1U << 3"],
    builders: ["addConstantBufferDynamic", "addInputAttachment"],
    interface: ["setGraphicsDescriptorSetDynamic", "beginOcclusionQuery", "createOcclusionQueryPool", "colorDiscard"],
}
for path, markers in required.items():
    data = path.read_text()
    missing = [m for m in markers if m not in data]
    if missing:
        raise SystemExit(f"{path}: missing required API markers: {missing}")

print("Verified Android Plume compatibility APIs in the exact headers used by the compiler.")
