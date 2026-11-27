#include "installer_legacy.h"

namespace maplesu::sdk {

KModErr install_maplesu_environment(InstallMode mode, const InstallConfig& config) {
    const maplesu::install::InstallOptions options{
        static_cast<maplesu::install::ExploitPolicy>(static_cast<uint32_t>(config.exploit))};
    return static_cast<KModErr>(maplesu::install::install_environment(
        static_cast<maplesu::install::DeploymentMode>(static_cast<uint32_t>(mode)), options));
}
KModErr uninstall_maplesu_environment() {
    return static_cast<KModErr>(maplesu::install::uninstall_environment());
}
KModErr get_installed_maplesu_environment_version(MapleSUSdkVersion& out) {
    maplesu::install::Version version;
    const auto error = maplesu::install::installed_version(version);
    if (error == maplesu::install::core::kOk) {
        out.major = version.major;
        out.minor = version.minor;
        out.patch = version.patch;
    }
    return static_cast<KModErr>(error);
}

}  // namespace maplesu::sdk

#include "install_installer_exports_legacy_abi.h"

