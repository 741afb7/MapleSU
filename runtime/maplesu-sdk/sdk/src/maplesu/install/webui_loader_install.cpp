#include "kernel_module_kit_umbrella.h"
#include "../../maplesu_assets/maplesu_payload_assets.h"
#include "private_maple_runtime.h"
#include "maplesu_storage_private.h"
#include "executable_publish.h"

#include <filesystem>
#include <string>
#include <sys/stat.h>
#include <sys/xattr.h>

namespace maplesu::sdk {

KModErr unsafe_install_webui_loader() {
    RETURN_IF_ERROR(default_modules::module_root::set_current_root(true));
    std::string directory;
    RETURN_IF_ERROR(storage::current_system_directory(directory));
    const std::string target = std::filesystem::path(directory) / "modules" / "webui_loader";

    static_assert(sizeof(webui_loader_data) == kWebuiLoaderPayloadSize || !kPayloadsReal,
                  "webui loader payload must match the selected asset size");
    constexpr char context[] = "u:object_r:system_file:s0";
    RETURN_IF_ERROR(maplesu_publish_executable(target.c_str(), webui_loader_data,
                                               sizeof(webui_loader_data), context));
    if (chmod(target.c_str(), 0777) != 0 ||
        setxattr(target.c_str(), "security.selinux", context, sizeof(context), 0) != 0)
        return KModErr::ERR_SET_FILE_SELINUX;
    return KModErr::OK;
}

}  // namespace maplesu::sdk

#include "../compat/install_webui_exports_legacy_abi.h"
