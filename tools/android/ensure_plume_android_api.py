#!/usr/bin/env python3
"""Ensure Android Plume API declarations exist in the exact compiler include tree."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLUME = ROOT / "thirdparty" / "plume"
types = PLUME / "plume_render_interface_types.h"
builders = PLUME / "plume_render_interface_builders.h"
interface = PLUME / "plume_render_interface.h"


def after(path, anchor, addition, marker, all_matches=False):
    s = path.read_text()
    if marker in s:
        return
    n = s.count(anchor)
    if n < 1 or (n != 1 and not all_matches):
        raise SystemExit(f"{path}: expected anchor for {marker}, found {n}")
    path.write_text(s.replace(anchor, anchor + addition, -1 if all_matches else 1))


def replace(path, old, new, marker):
    s = path.read_text()
    if marker in s:
        return
    if s.count(old) != 1:
        raise SystemExit(f"{path}: expected one enum anchor for {marker}, found {s.count(old)}")
    path.write_text(s.replace(old, new, 1))


after(types, "        BC7_UNORM_SRGB,\n",
      "        B5G6R5_UNORM,  // Opaque 16-bit DXT1 colour.\n", "B5G6R5_UNORM")
replace(types, "        ACCELERATION_STRUCTURE\n",
        "        ACCELERATION_STRUCTURE,\n        INPUT_ATTACHMENT,\n        CONSTANT_BUFFER_DYNAMIC\n",
        "CONSTANT_BUFFER_DYNAMIC")
after(types, "            COPY = 1U << 2,\n",
      "            FRAGMENT = 1U << 3,\n", "FRAGMENT = 1U << 3")

after(types, "        bool depthAttachmentReadOnly = false;\n",
      "        bool colorDiscard = false;\n", "colorDiscard")
after(types, "        case RenderFormat::R16_FLOAT:\n",
      "        case RenderFormat::B5G6R5_UNORM:\n", "case RenderFormat::B5G6R5_UNORM", all_matches=True)

after(builders,
      "        uint32_t addConstantBuffer(uint32_t binding, uint32_t count = 1) {\n"
      "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::CONSTANT_BUFFER, binding, count, nullptr));\n"
      "        }\n",
      "\n        uint32_t addConstantBufferDynamic(uint32_t binding, uint32_t count = 1) {\n"
      "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::CONSTANT_BUFFER_DYNAMIC, binding, count, nullptr));\n"
      "        }\n", "addConstantBufferDynamic")
after(builders,
      "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::READ_WRITE_TEXTURE, binding, count, nullptr));\n"
      "        }\n",
      "\n        uint32_t addInputAttachment(uint32_t binding, uint32_t count = 1) {\n"
      "            return addRange(RenderDescriptorRange(RenderDescriptorRangeType::INPUT_ATTACHMENT, binding, count, nullptr));\n"
      "        }\n", "addInputAttachment")

after(interface,
      "        virtual void setGraphicsDescriptorSet(RenderDescriptorSet *descriptorSet, uint32_t setIndex) = 0;\n",
      "        virtual void setGraphicsDescriptorSetDynamic(RenderDescriptorSet *descriptorSet, uint32_t setIndex, const uint32_t *dynamicOffsets, uint32_t dynamicOffsetCount) {}\n",
      "setGraphicsDescriptorSetDynamic")
after(interface,
      "        virtual void writeTimestamp(const RenderQueryPool *queryPool, uint32_t queryIndex) = 0;\n",
      "        virtual void beginOcclusionQuery(const RenderQueryPool *queryPool, uint32_t queryIndex) {}\n"
      "        virtual void endOcclusionQuery(const RenderQueryPool *queryPool, uint32_t queryIndex) {}\n",
      "beginOcclusionQuery")
after(interface,
      "        virtual void writeTimestamp(const RenderQueryPool *queryPool, uint32_t queryIndex) = 0;\n",
      "        virtual void setFragmentShadingRate(uint32_t width, uint32_t height) {}\n",
      "setFragmentShadingRate")
after(interface,
      "        virtual std::unique_ptr<RenderQueryPool> createQueryPool(uint32_t queryCount) = 0;\n",
      "        virtual std::unique_ptr<RenderQueryPool> createOcclusionQueryPool(uint32_t queryCount) { return nullptr; }\n",
      "createOcclusionQueryPool")

checks = {
    types: ["B5G6R5_UNORM", "INPUT_ATTACHMENT", "CONSTANT_BUFFER_DYNAMIC", "FRAGMENT = 1U << 3", "colorDiscard"],
    builders: ["addConstantBufferDynamic", "addInputAttachment"],
    interface: ["setGraphicsDescriptorSetDynamic", "beginOcclusionQuery", "setFragmentShadingRate", "createOcclusionQueryPool"],
}
for path, markers in checks.items():
    s = path.read_text()
    missing = [m for m in markers if m not in s]
    if missing:
        raise SystemExit(f"{path}: missing API markers after compatibility step: {missing}")
print("PASS: Android Plume API declarations verified in compiler include directory.")
