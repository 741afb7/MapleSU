#include "kernel_module_kit_umbrella.h"

#include "bootstrap_install.h"
#include "core.h"
#include "environment.h"
#include "lifecycle.h"
#include "../compat/installer_legacy.h"
#include "platform.h"

#include <cstdio>
#include <filesystem>
#include <new>
#include <sys/types.h>
#include <unistd.h>
#include <string>

extern "C" uint32_t maplesu_environment_state();
extern "C" pid_t maplesu_wait_environment_child(pid_t child, int* status);

namespace maplesu::install {

Version sdk_version() {
    Version version{};
    std::sscanf(kSdkVersionText, "%u.%u.%u", &version.major, &version.minor,
                &version.patch);
    return version;
}


EnvironmentState environment_state() {
    const auto auth = kernel_module::maplesu_request_auth_state();
    if (!kernel_module::maplesu_request_auth_usable(auth) ||
        kernel_module::maplesu_request_auth_helper(auth) ||
        kernel_module::maplesu_request_worker_admission() != KModErr::OK)
        return EnvironmentState::Unknown;

    compat::InstallerChannel pipe;
    if (pipe.start()) {
        const int state = static_cast<int>(maplesu_environment_state());
        (void)pipe.write_integer(state);
        pipe.close();
        ::_exit(0);
    }

    int state = static_cast<int>(EnvironmentState::Unknown);
    const bool read_ok = pipe.read_integer(state);

    int child_status = 0;
    const int child_pid = pipe.child_pid();
    const pid_t waited = maplesu_wait_environment_child(static_cast<pid_t>(child_pid), &child_status);

    const bool reply_ok = read_ok && waited == child_pid && child_pid > 0 &&
                          child_status == 0 && static_cast<unsigned int>(state) < 4u;
    return reply_ok ? static_cast<EnvironmentState>(state) : EnvironmentState::Unknown;
}

namespace {
static std::string get_kernel_file_dir_path() {
    std::string directory;
    if (storage::current_kernel_directory(directory) != KModErr::OK) return {};
    return directory;
}

static std::string get_autorun_bootstrap_path() {
    const std::string directory = get_kernel_file_dir_path();
    if (directory.empty()) return {};
    return std::filesystem::path(directory) / "init";
}

static std::string version_path() {
    const std::string directory = get_kernel_file_dir_path();
    if (directory.empty()) return {};
    return std::filesystem::path(directory) / compat::kInstallerVersionFile;
}
}  // namespace

void environment_paths(std::string* helper, std::string* version) {
    ::new (static_cast<void*>(helper)) std::string(get_autorun_bootstrap_path());
    ::new (static_cast<void*>(version)) std::string(version_path());
}


namespace {

using core::err_t;

err_t reset_flag_file(const char* suffix) {
    const err_t set_root_err =
        static_cast<err_t>(default_modules::module_root::set_current_root(true));
    if (set_root_err != core::kOk) return set_root_err;

    const std::string base = environment::kernel_file_path("init");
    if (base.empty()) return core::kErrModuleParam;
    const std::string flag_path = base + suffix;

    return static_cast<err_t>(publish_empty_state(flag_path.c_str(), core::kSelinuxKernel));
}

KModErr reset_safe_flag() {
    return static_cast<KModErr>(reset_flag_file(".safe_flag"));
}

KModErr reset_duplicate_boot_session_flag() {
    return static_cast<KModErr>(reset_flag_file(".boot_session.duplicate"));
}

}  // namespace
}  // namespace maplesu::install

namespace maplesu::sdk {

MapleSUEnvState get_maplesu_environment_state() {
    return static_cast<MapleSUEnvState>(static_cast<uint32_t>(maplesu::install::environment_state()));
}

MapleSUSdkVersion get_sdk_version() {
    const auto version = maplesu::install::sdk_version();
    return {version.major, version.minor, version.patch};
}

__attribute__((visibility("hidden"))) KModErr unsafe_reset_autorun_bootstrap_safe_flag() {
    return maplesu::install::reset_safe_flag();
}

__attribute__((visibility("hidden"))) KModErr unsafe_reset_autorun_bootstrap_duplicate_boot_session_flag() {
    return maplesu::install::reset_duplicate_boot_session_flag();
}

}  // namespace maplesu::sdk

extern "C" __attribute__((visibility("hidden")))
void maplesu_installed_helper_path(std::string* out) {
    if (out == nullptr) return;
    *out = maplesu::install::environment::kernel_file_path("init");
}

extern "C" __attribute__((visibility("hidden")))
void maplesu_environment_paths(std::string* helper, std::string* version) {
    maplesu::install::environment_paths(helper, version);
}

#include "../compat/install_state_exports_legacy_abi.h"
