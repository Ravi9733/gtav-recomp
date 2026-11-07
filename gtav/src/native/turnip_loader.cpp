// Android Turnip loader bridge. Keep the custom Vulkan loader alive for the process lifetime.
#if defined(__ANDROID__)
#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {
using OpenVulkan = void* (*)(int, int, const char*, const char*, const char*, const char*, const char*, void**);
void* g_adrenotools = nullptr;
void* g_vulkan = nullptr;
void* g_gipa = nullptr;

void Log(int priority, const char* message) {
  __android_log_write(priority, "GtavTurnip", message);
}
}

extern "C" void gtav_android_turnip_report_driver(const char* name, const char* info) {
  if (name && *name) setenv("GTAV_VULKAN_DRIVER_NAME", name, 1);
  if (info && *info) setenv("GTAV_VULKAN_DRIVER_INFO", info, 1);
  __android_log_print(ANDROID_LOG_INFO, "GtavTurnip", "Vulkan driver identity: %s | %s",
                      name ? name : "unknown", info ? info : "no driver info");
}

extern "C" void* gtav_android_turnip_get_gipa() {
  const char* enabled = std::getenv("GTAV_TURNIP_ENABLED");
  if (!enabled || std::strcmp(enabled, "1") != 0) return nullptr;
  if (g_gipa) return g_gipa;

  const char* hook_dir = std::getenv("GTAV_TURNIP_HOOK_LIB_DIR");
  const char* driver_dir = std::getenv("GTAV_TURNIP_DRIVER_DIR");
  const char* driver_name = std::getenv("GTAV_TURNIP_DRIVER_NAME");
  if (!hook_dir || !*hook_dir || !driver_dir || !*driver_dir || !driver_name || !*driver_name) {
    Log(ANDROID_LOG_WARN, "Turnip requested without private driver paths; using system Vulkan.");
    return nullptr;
  }
  if (driver_dir[std::strlen(driver_dir) - 1] != '/') {
    Log(ANDROID_LOG_ERROR, "Turnip driver directory must end with '/'; using system Vulkan.");
    return nullptr;
  }
  const std::string driver_path = std::string(driver_dir) + driver_name;
  struct stat st {};
  if (stat(driver_path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
    Log(ANDROID_LOG_WARN, "Turnip driver file is missing; using system Vulkan.");
    return nullptr;
  }

  g_adrenotools = dlopen("libadrenotools.so", RTLD_NOW | RTLD_LOCAL);
  if (!g_adrenotools) {
    Log(ANDROID_LOG_ERROR, "Could not load packaged libadrenotools.so; using system Vulkan.");
    return nullptr;
  }
  auto open_vulkan = reinterpret_cast<OpenVulkan>(dlsym(g_adrenotools, "adrenotools_open_libvulkan"));
  if (!open_vulkan) {
    Log(ANDROID_LOG_ERROR, "adrenotools_open_libvulkan is missing; using system Vulkan.");
    return nullptr;
  }

  // ADRENOTOOLS_DRIVER_CUSTOM=1; hooks live in nativeLibraryDir, driver in app-private storage.
  g_vulkan = open_vulkan(RTLD_NOW, 1, nullptr, hook_dir, driver_dir, driver_name, nullptr, nullptr);
  if (!g_vulkan) {
    Log(ANDROID_LOG_ERROR, "AdrenoTools could not open Turnip; using system Vulkan.");
    return nullptr;
  }
  g_gipa = dlsym(g_vulkan, "vkGetInstanceProcAddr");
  if (!g_gipa) {
    Log(ANDROID_LOG_ERROR, "Turnip loader has no vkGetInstanceProcAddr; using system Vulkan.");
    g_vulkan = nullptr;
    return nullptr;
  }
  Log(ANDROID_LOG_INFO, "Custom Vulkan loader opened; Plume will initialize Volk through Turnip.");
  return g_gipa;
}
#endif
