#include <cstdint>

// These globals are imported by libgtav_game.so from libgtav_fast.so at dlopen time.
// Keep the definitions in the host shared-library target, not the Plume static archive.
namespace plume {
#if defined(__GNUC__)
__attribute__((visibility("default"))) std::uint32_t g_statRenderPasses = 0;
__attribute__((visibility("default"))) std::uint32_t g_statLoadClears = 0;
__attribute__((visibility("default"))) std::uint32_t g_statBarriers = 0;
__attribute__((visibility("default"))) std::uint32_t g_statClearsPartial = 0;
__attribute__((visibility("default"))) std::uint32_t g_statClearsInPass = 0;
__attribute__((visibility("default"))) std::uint32_t g_exactBarrierStages = 0;
__attribute__((visibility("default"))) std::uint32_t g_clearLoadOps = 0;
__attribute__((visibility("default"))) bool g_colorInputAttachments = false;
#else
std::uint32_t g_statRenderPasses = 0;
std::uint32_t g_statLoadClears = 0;
std::uint32_t g_statBarriers = 0;
std::uint32_t g_statClearsPartial = 0;
std::uint32_t g_statClearsInPass = 0;
std::uint32_t g_exactBarrierStages = 0;
std::uint32_t g_clearLoadOps = 0;
#endif
}  // namespace plume
