#include "menu.h"

#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif
#include "touch_controls.h"

#include "paths.h"

#include <imgui.h>
#include <rex/cvar.h>
#include <rex/logging.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cctype>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

namespace gtav::native::menu {

namespace {

struct Input {
  enum Type { kMove, kButton, kWheel, kKey } type;
  float x = 0, y = 0;
  int code = 0;
  bool down = false;
};

std::mutex g_input_mutex;
std::vector<Input> g_input;
std::atomic<bool> g_open{false};

ImGuiContext* g_context = nullptr;
bool g_font_uploaded = false;
float g_scale = 1.0f;

// Every setting the menu shows, by cvar name. Saved to gtav_settings.ini as name=value.
constexpr const char* kSettings[] = {
    "gtav_fps_limit",
    "gtav_native_vsync",
    "gtav_native_fps",
    "gtav_native_tile_penumbra_all",
    "gtav_native_async_pipelines",
    "gpu_skip_rendering_lag_ms",
    "gtav_native_exact_cache",
    "gtav_native_texture_recheck_frames",
    "gtav_native_texture_min_lod",
    "gtav_native_max_anisotropy",
    "gtav_touch_controls",
};

std::filesystem::path SettingsPath() { return ExeDir() / "gtav_settings.ini"; }

void SaveSettings() {
  std::ofstream out(SettingsPath());
  out << "# GTA V native renderer settings (Esc menu). name=value, applied over the command line.\n";
  for (const char* name : kSettings) out << name << '=' << rex::cvar::GetFlagByName(name) << '\n';
}

bool GetBool(const char* name) { return rex::cvar::GetFlagByName(name) == "true"; }
void SetBool(const char* name, bool value) { rex::cvar::SetFlagByName(name, value ? "true" : "false"); }

// Presets: the two ends of the trade-off. "Best image" draws everything exactly as soon as it
// appears (new pipelines are built on the spot, a short hitch the first time an effect shows up)
// with the per-pixel sun shadows; "Max performance" keeps the frame moving (new effects are
// skipped for a frame or two while they compile), lets the game skip shadow work per tile and
// lets the emulated GPU trail the game less.
// How far the emulated GPU trails the game's kicks (ms): 6 is the tested value (foliage stopped
// flickering while driving); 2 flickered. 4 is ~10% faster on Windows (88 -> 98 fps running
// around, 10-29) and no worse than 6 in a headless flicker-catch A/B; not yet checked driving.
constexpr const char* kSafeGpuLag = "6";
constexpr const char* kFastGpuLag = "4";

void ApplyPreset(bool best) {
#ifdef __ANDROID__
  // Phones: the per-pixel sun tiles cost no measurable fps (same-run A/B on an Adreno 730), and
  // without them the game's per-tile shortcut shows gray 16x16 blocks at object edges.
  SetBool("gtav_native_tile_penumbra_all", true);
#else
  SetBool("gtav_native_tile_penumbra_all", best);
#endif
  SetBool("gtav_native_async_pipelines", !best);
  // The image-quality profile must never inherit a reduced-detail texture setting from an
  // earlier performance experiment. The performance profile leaves texture quality untouched.
  if (best) {
    rex::cvar::SetFlagByName("gtav_native_texture_min_lod", "0");
    rex::cvar::SetFlagByName("gtav_native_max_anisotropy", "16");
  }
  rex::cvar::SetFlagByName("gpu_skip_rendering_lag_ms", best ? kSafeGpuLag : kFastGpuLag);
#ifdef __ANDROID__
  // Phones: "Best image" is also the steady one, locked at 30 fps with vsync (sustained play heats
  // the phone less and throttles later); "Max performance" runs uncapped.
  rex::cvar::SetFlagByName("gtav_fps_limit", best ? "30" : "0");
  SetBool("gtav_native_vsync", best);
#else
  if (!best) {
    rex::cvar::SetFlagByName("gtav_fps_limit", "0");
    SetBool("gtav_native_vsync", false);
  }
#endif
  SaveSettings();
}

void EnsureContext(uint32_t h) {
  if (g_context) return;
  IMGUI_CHECKVERSION();
  ImGuiContext* previous = ImGui::GetCurrentContext();
  g_context = ImGui::CreateContext();
  ImGui::SetCurrentContext(g_context);
  ImGuiIO& io = ImGui::GetIO();
  io.IniFilename = nullptr;
  io.LogFilename = nullptr;
  io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
  g_scale = std::max(1.0f, float(h) / 720.0f);
#if defined(__APPLE__) && TARGET_OS_IPHONE
  g_scale *= 1.6f;  // a 5.4-inch screen driven by fingers: larger text and controls
#endif
  ImFontConfig font;
  font.SizePixels = 15.0f * g_scale;
  io.Fonts->AddFontDefault(&font);
  ImGui::StyleColorsDark();
  ImGuiStyle& style = ImGui::GetStyle();
  // A compact, touch-first control centre: soft panels, clear hierarchy and a restrained
  // blue accent. Keep this in the existing renderer overlay so no second graphics API is needed.
  style.WindowRounding = 16.0f;
  style.ChildRounding = 12.0f;
  style.FrameRounding = 9.0f;
  style.PopupRounding = 10.0f;
  style.ScrollbarRounding = 10.0f;
  style.GrabRounding = 9.0f;
  style.WindowBorderSize = 0.0f;
  style.FrameBorderSize = 0.0f;
  style.WindowPadding = ImVec2(22.0f, 20.0f);
  style.FramePadding = ImVec2(14.0f, 10.0f);
  style.ItemSpacing = ImVec2(12.0f, 11.0f);
  style.ItemInnerSpacing = ImVec2(9.0f, 7.0f);
  ImVec4* colors = style.Colors;
  colors[ImGuiCol_WindowBg] = ImVec4(0.035f, 0.047f, 0.071f, 0.97f);
  colors[ImGuiCol_ChildBg] = ImVec4(0.060f, 0.075f, 0.105f, 1.00f);
  colors[ImGuiCol_PopupBg] = ImVec4(0.055f, 0.067f, 0.092f, 0.98f);
  colors[ImGuiCol_Border] = ImVec4(0.15f, 0.19f, 0.26f, 0.65f);
  colors[ImGuiCol_FrameBg] = ImVec4(0.105f, 0.13f, 0.18f, 1.00f);
  colors[ImGuiCol_FrameBgHovered] = ImVec4(0.15f, 0.22f, 0.32f, 1.00f);
  colors[ImGuiCol_FrameBgActive] = ImVec4(0.17f, 0.27f, 0.40f, 1.00f);
  colors[ImGuiCol_TitleBg] = ImVec4(0.035f, 0.047f, 0.071f, 1.00f);
  colors[ImGuiCol_TitleBgActive] = ImVec4(0.035f, 0.047f, 0.071f, 1.00f);
  colors[ImGuiCol_Button] = ImVec4(0.12f, 0.32f, 0.58f, 1.00f);
  colors[ImGuiCol_ButtonHovered] = ImVec4(0.16f, 0.42f, 0.72f, 1.00f);
  colors[ImGuiCol_ButtonActive] = ImVec4(0.10f, 0.27f, 0.49f, 1.00f);
  colors[ImGuiCol_CheckMark] = ImVec4(0.35f, 0.68f, 1.00f, 1.00f);
  colors[ImGuiCol_SliderGrab] = ImVec4(0.25f, 0.55f, 0.90f, 1.00f);
  colors[ImGuiCol_SliderGrabActive] = ImVec4(0.40f, 0.72f, 1.00f, 1.00f);
  colors[ImGuiCol_Header] = ImVec4(0.12f, 0.27f, 0.43f, 0.75f);
  colors[ImGuiCol_HeaderHovered] = ImVec4(0.16f, 0.36f, 0.56f, 0.85f);
  colors[ImGuiCol_HeaderActive] = ImVec4(0.13f, 0.31f, 0.50f, 1.00f);
  style.ScaleAllSizes(g_scale);
  if (previous) ImGui::SetCurrentContext(previous);
}

void Push(const Input& in) {
  if (!g_open) return;
  std::lock_guard lock(g_input_mutex);
  if (g_input.size() < 1024) g_input.push_back(in);
}

ImGuiKey ToImGui(Key key) {
  switch (key) {
    case Key::kUp: return ImGuiKey_UpArrow;
    case Key::kDown: return ImGuiKey_DownArrow;
    case Key::kLeft: return ImGuiKey_LeftArrow;
    case Key::kRight: return ImGuiKey_RightArrow;
    case Key::kEnter: return ImGuiKey_Enter;
    case Key::kSpace: return ImGuiKey_Space;
    case Key::kTab: return ImGuiKey_Tab;
  }
  return ImGuiKey_None;
}

void Build(uint32_t fps, double frame_ms) {
#if defined(__ANDROID__) || (defined(__APPLE__) && TARGET_OS_IPHONE)
  // Centre the touch-first control centre on phones; keep it clear of rounded corners and notches.
  const ImVec2 display = ImGui::GetIO().DisplaySize;
  ImGui::SetNextWindowPos(ImVec2(display.x * 0.5f, display.y * 0.5f), ImGuiCond_Always, ImVec2(0.5f, 0.5f));
#else
  ImGui::SetNextWindowPos(ImVec2(40.0f * g_scale, 40.0f * g_scale), ImGuiCond_FirstUseEver);
#endif
  ImGui::SetNextWindowSize(ImVec2(std::min(620.0f * g_scale, ImGui::GetIO().DisplaySize.x - 32.0f * g_scale), 0.0f), ImGuiCond_FirstUseEver);
  bool open = true;
  if (!ImGui::Begin("GTA V  |  CONTROL CENTER", &open, ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoTitleBar)) {
    ImGui::End();
    return;
  }
  ImGui::Text("%u fps  (%.1f ms per frame)", fps, frame_ms);
  ImGui::Separator();

  ImGui::TextUnformatted("PERFORMANCE PROFILE");
  if (ImGui::Button("Best image")) ApplyPreset(true);
  ImGui::SameLine();
  if (ImGui::Button("Max performance")) ApplyPreset(false);
  ImGui::TextDisabled("Compare the fps above after switching.");
  ImGui::Separator();

  bool changed = false;
  int limit = std::atoi(rex::cvar::GetFlagByName("gtav_fps_limit").c_str());
  int choice = limit == 30 ? 0 : limit == 60 ? 1 : limit <= 0 ? 2 : 3;
  const char* limits[] = {"30 (the game's own limiter)", "60", "Uncapped", "Custom (command line)"};
  ImGui::SetNextItemWidth(260.0f * g_scale);
  if (ImGui::Combo("Frame rate limit", &choice, limits, choice == 3 ? 4 : 3) && choice < 3) {
    rex::cvar::SetFlagByName("gtav_fps_limit", choice == 0 ? "30" : choice == 1 ? "60" : "0");
    changed = true;
  }

  auto check = [&](const char* label, const char* name, const char* help) {
    bool v = GetBool(name);
    if (ImGui::Checkbox(label, &v)) {
      SetBool(name, v);
      changed = true;
    }
    if (help && ImGui::IsItemHovered()) ImGui::SetTooltip("%s", help);
  };
  check("VSync", "gtav_native_vsync", "Wait for the display refresh: no tearing, fps capped at the refresh rate.");
  check("FPS counter", "gtav_native_fps", "Frames per second in the top-left corner.");
  ImGui::Separator();
  ImGui::TextUnformatted("RENDER QUALITY");
  check("Per-pixel sun shadows", "gtav_native_tile_penumbra_all",
        "On: every screen tile computes sun shadows per pixel (no blocky shadows or car reflections).\n"
        "Off: the game's own per-tile shortcut, which goes blocky on this renderer (gray 16x16 blocks).");
  check("Compile new effects in the background", "gtav_native_async_pipelines",
        "On: no hitch when a new effect first appears; it is skipped for a frame or two instead.\n"
        "Off: it is drawn from its first frame, with a short hitch while it is built.");
  {
    bool fast = std::atoi(rex::cvar::GetFlagByName("gpu_skip_rendering_lag_ms").c_str()) < std::atoi(kSafeGpuLag);
    if (ImGui::Checkbox("Low GPU latency", &fast)) {
      rex::cvar::SetFlagByName("gpu_skip_rendering_lag_ms", fast ? kFastGpuLag : kSafeGpuLag);
      changed = true;
    }
    if (ImGui::IsItemHovered())
      ImGui::SetTooltip("On: the emulated GPU trails the game by 2 ms instead of 6 (about 20%% more fps).\n"
                        "Off: the tested setting; with On, watch foliage and distant meshes while driving.");
  }
  ImGui::Separator();
  ImGui::TextUnformatted("TEXTURE STREAMING");
  if (ImGui::Button("Restore full texture detail")) {
    // Restore the original-resolution mip choice and the strongest filtering cap without
    // changing game files or forcing expensive full-cache validation every frame.
    rex::cvar::SetFlagByName("gtav_native_texture_min_lod", "0");
    rex::cvar::SetFlagByName("gtav_native_max_anisotropy", "16");
    rex::cvar::SetFlagByName("gtav_native_texture_recheck_frames", "4");
    changed = true;
  }
  if (ImGui::IsItemHovered())
    ImGui::SetTooltip("Full-resolution mip levels, up to 16x anisotropic filtering, and Androids 4-frame texture re-check interval.");
  check("Exact texture checks", "gtav_native_exact_cache",
        "On: every texture is fully re-checked each frame: no stale gray blocks, but more CPU time.\n"
        "Off: large textures are spot-checked between full checks (faster).");
  {
    int frames = std::atoi(rex::cvar::GetFlagByName("gtav_native_texture_recheck_frames").c_str());
    ImGui::SetNextItemWidth(200.0f * g_scale);
    if (ImGui::SliderInt("Full re-check every N frames", &frames, 1, 30)) {
      rex::cvar::SetFlagByName("gtav_native_texture_recheck_frames", std::to_string(frames));
      changed = true;
    }
    if (ImGui::IsItemHovered())
      ImGui::SetTooltip("Lower: textures the game streams in show up sooner (fewer gray blocks), more CPU.");
  }
  {
    int mip = std::atoi(rex::cvar::GetFlagByName("gtav_native_texture_min_lod").c_str());
    ImGui::SetNextItemWidth(260.0f * g_scale);
    if (ImGui::SliderInt("Texture detail bias", &mip, 0, 4)) {
      rex::cvar::SetFlagByName("gtav_native_texture_min_lod", std::to_string(mip));
      changed = true;
    }
    if (ImGui::IsItemHovered())
      ImGui::SetTooltip("0 keeps full texture detail. Higher values use smaller mip levels to reduce memory bandwidth.");
  }
  {
    int anisotropy = std::atoi(rex::cvar::GetFlagByName("gtav_native_max_anisotropy").c_str());
    if (anisotropy < 1) anisotropy = 1;
    ImGui::SetNextItemWidth(260.0f * g_scale);
    if (ImGui::SliderInt("Anisotropic filtering", &anisotropy, 1, 16)) {
      rex::cvar::SetFlagByName("gtav_native_max_anisotropy", std::to_string(anisotropy));
      changed = true;
    }
    if (ImGui::IsItemHovered())
      ImGui::SetTooltip("Higher values keep road and ground textures sharper at oblique viewing angles.");
  }
#ifdef __ANDROID__
  ImGui::Separator();
  ImGui::TextUnformatted("VULKAN DEVICE");
  ImGui::Text("Preferred GPU: %s", rex::cvar::GetFlagByName("gtav_native_device").c_str());
  const char* turnipRequest = std::getenv("GTAV_TURNIP_ENABLED");
  const bool turnipRequested = turnipRequest && std::string(turnipRequest) == "1";
  ImGui::Text("Turnip request: %s", turnipRequested ? "ON (selected driver)" : "OFF (system Vulkan)");
  const char* activeDriver = std::getenv("GTAV_VULKAN_DRIVER_NAME");
  const char* activeDriverInfo = std::getenv("GTAV_VULKAN_DRIVER_INFO");
  if (activeDriver && *activeDriver) {
    std::string driverLower(activeDriver);
    std::transform(driverLower.begin(), driverLower.end(), driverLower.begin(),
                   [](unsigned char ch) { return char(std::tolower(ch)); });
    if (driverLower.find("turnip") != std::string::npos)
      ImGui::Text("Active Vulkan driver: %s  [verified]", activeDriver);
    else
      ImGui::Text("Active Vulkan driver: %s", activeDriver);
    if (activeDriverInfo && *activeDriverInfo) ImGui::TextDisabled("%s", activeDriverInfo);
  } else {
    ImGui::TextDisabled("Active Vulkan driver identity is not available from this driver.");
  }
#endif
  ImGui::TextDisabled("Import or enable Turnip: long-press the app icon > GPU driver settings, then restart the game.");
  ImGui::Separator();
  check("Touch controls", "gtav_touch_controls", "The on-screen gamepad (hidden while a controller is connected).");
  ImGui::TextDisabled("CONTROLS  ·  RB + Back opens menu  ·  D-pad + A selects  ·  B closes");
  if (changed) SaveSettings();
  ImGui::End();
  if (!open) g_open = false;
}

}  // namespace

void OnEscape() {
  bool was_open = g_open.exchange(!g_open.load());
  if (!was_open) {
    std::lock_guard lock(g_input_mutex);
    g_input.clear();
  }
}
bool IsOpen() { return g_open.load(); }
void OnMouseMove(float x, float y) { Push({Input::kMove, x, y}); }
void OnMouseButton(int button, bool down) { Push({Input::kButton, 0, 0, button, down}); }
void OnMouseWheel(float dy) { Push({Input::kWheel, 0, dy}); }
void OnKey(Key key, bool down) { Push({Input::kKey, 0, 0, int(key), down}); }

void TestSet(const std::string& name, const std::string& value) {
  REXLOG_INFO("[native] menu test: {} = {}", name, value);
  rex::cvar::SetFlagByName(name, value);
  if (std::getenv("GTAV_IGNORE_SETTINGS")) return;  // benchmark runs leave gtav_settings.ini alone
  SaveSettings();
  REXLOG_INFO("[native] menu test: saved");
}

void LoadSettings() {
  // GTAV_IGNORE_SETTINGS=1: command-line settings only (benchmark runs).
  if (std::getenv("GTAV_IGNORE_SETTINGS")) return;
  std::ifstream in(SettingsPath());
  if (!in) return;
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty() || line[0] == '#') continue;
    auto eq = line.find('=');
    if (eq == std::string::npos) continue;
    std::string name = line.substr(0, eq), value = line.substr(eq + 1);
    if (std::find_if(std::begin(kSettings), std::end(kSettings), [&](const char* s) { return name == s; }) ==
        std::end(kSettings))
      continue;
    if (rex::cvar::SetFlagByName(name, value)) REXLOG_INFO("[native] setting {} = {} (gtav_settings.ini)", name, value);
  }
}

ImDrawData* Frame(uint32_t w, uint32_t h, uint32_t fps, double frame_ms) {
  const bool touch_overlay = touch::Visible();
  if (!g_open && !touch_overlay) return nullptr;
  EnsureContext(h);
  ImGuiContext* previous = ImGui::GetCurrentContext();
  ImGui::SetCurrentContext(g_context);
  ImGuiIO& io = ImGui::GetIO();
  io.DisplaySize = ImVec2(float(w), float(h));
  io.DeltaTime = 1.0f / 60.0f;
  {
    std::lock_guard lock(g_input_mutex);
    for (const auto& in : g_input) {
      switch (in.type) {
        case Input::kMove: io.AddMousePosEvent(in.x, in.y); break;
        case Input::kButton: io.AddMouseButtonEvent(in.code, in.down); break;
        case Input::kWheel: io.AddMouseWheelEvent(0.0f, in.y); break;
        case Input::kKey: io.AddKeyEvent(ToImGui(Key(in.code)), in.down); break;
      }
    }
    g_input.clear();
  }
  if (!g_font_uploaded) {  // legacy atlas: the backend uploads it before the first draw
    unsigned char* pixels;
    int fw, fh;
    io.Fonts->GetTexDataAsRGBA32(&pixels, &fw, &fh);
  }
  ImGui::NewFrame();
  if (g_open) Build(fps, frame_ms);
  if (touch_overlay) touch::Draw(ImGui::GetForegroundDrawList(), float(w), float(h));
  ImGui::Render();
  ImDrawData* data = ImGui::GetDrawData();
  if (previous && previous != g_context) ImGui::SetCurrentContext(previous);
  return data;
}

bool TakeFontAtlas(const uint8_t** pixels, int* width, int* height) {
  if (g_font_uploaded || !g_context) return false;
  ImGuiContext* previous = ImGui::GetCurrentContext();
  ImGui::SetCurrentContext(g_context);
  unsigned char* p;
  ImGui::GetIO().Fonts->GetTexDataAsRGBA32(&p, width, height);
  *pixels = p;
  if (previous && previous != g_context) ImGui::SetCurrentContext(previous);
  return true;
}

void SetFontTexture(uint32_t descriptor) {
  ImGuiContext* previous = ImGui::GetCurrentContext();
  ImGui::SetCurrentContext(g_context);
  ImGui::GetIO().Fonts->SetTexID(ImTextureID(descriptor));
  g_font_uploaded = true;
  if (previous && previous != g_context) ImGui::SetCurrentContext(previous);
}

}  // namespace gtav::native::menu
