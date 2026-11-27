// MapleSU unsafe su install implementation.
//
// Publishes separate system and kernel directory copies of su.
#include "kernel_module_kit_umbrella.h"

#include "maplesu_payload_assets.h"
#include "private_maple_runtime.h"
#include "maplesu_storage_private.h"
#include "../maplesu/install/executable_publish.h"

#include <string>

extern "C" KModErr maplesu_prepare_su_exec_directory(const char*);

namespace maplesu::sdk {

constexpr KModErr kOk = KModErr::OK;

// Publish the same keyless program at both existing deployment paths.
static KModErr write_su_exec(const char* target_path) {
    static_assert(sizeof(su_data) == kSuPayloadSize || !kPayloadsReal,
                  "su payload must match the selected asset size");
    return maplesu_publish_executable(target_path, su_data, sizeof(su_data),
                                      "u:object_r:system_file:s0");
}

}  // namespace maplesu::sdk

namespace maplesu::sdk {

// Installs the su program at the runtime data directory and labels it.
KModErr unsafe_install_su() {
    const KModErr set_root_err = default_modules::module_root::set_current_root(true);
    if (set_root_err != kOk) return set_root_err;

    std::string system_directory, kernel_directory;
    RETURN_IF_ERROR(maplesu::storage::current_system_directory(system_directory));
    RETURN_IF_ERROR(maplesu::storage::current_kernel_directory(kernel_directory));
    const std::string su_path = system_directory + "su";
    const std::string su_path2 = kernel_directory + "su";

    KModErr err = write_su_exec(su_path.c_str());
    if (err != kOk) return err;
    err = write_su_exec(su_path2.c_str());
    if (err != kOk) return err;

    return maplesu_prepare_su_exec_directory(system_directory.c_str());
}

}  // namespace maplesu::sdk

#include "../maplesu/compat/install_su_exports_legacy_abi.h"
