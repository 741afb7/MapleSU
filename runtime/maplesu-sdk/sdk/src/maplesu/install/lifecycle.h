#pragma once

#include <cstdint>
#include <string>
#include "core.h"

namespace maplesu::install {

enum class DeploymentMode : uint32_t { Boot = 0, HotLoad = 1, Refresh = 2 };
enum class ExploitPolicy : uint32_t { None = 0, Magica = 1, CVE2026_43499 = 2 };
struct InstallOptions { ExploitPolicy exploit = ExploitPolicy::None; };
enum class EnvironmentState : uint32_t { NotInstalled = 0, Running = 1, Fault = 2, Unknown = 3 };
struct Version { uint32_t major = 0, minor = 0, patch = 0; };
inline constexpr char kSdkVersionText[] = "5.2.1";
static_assert(sizeof(DeploymentMode) == 4 && sizeof(ExploitPolicy) == 4);
static_assert(sizeof(InstallOptions) == 4 && sizeof(EnvironmentState) == 4);
static_assert(sizeof(Version) == 12 && alignof(Version) == 4);

__attribute__((visibility("hidden")))
core::err_t install_environment(DeploymentMode, const InstallOptions& = InstallOptions());
__attribute__((visibility("hidden"))) core::err_t uninstall_environment();
__attribute__((visibility("hidden"))) EnvironmentState environment_state();
__attribute__((visibility("hidden"))) core::err_t installed_version(Version&);
__attribute__((visibility("hidden"))) Version sdk_version();
__attribute__((visibility("hidden"))) void environment_paths(std::string*, std::string*);

}  // namespace maplesu::install
