#pragma once
#include <cstdint>
#include <tuple>
#include "helper_core.h"
#include "module_err_def.h"
#include "../../maplesu/module/types.h"
#include "maplesu_sdk/maplesu_installer.h"
#include "maplesu_storage_worker_private.h"

namespace maplesu::bootstrap {
KModErr select_worker(helper_context& context, std::optional<storage::WorkerScope>& worker,
                     kernel_module::RequestAuthMode mode);
KModErr initialize(helper_context& context, std::optional<storage::WorkerScope>& worker);
KModErr initialize_core(helper_context& context, std::optional<storage::WorkerScope>& worker);
KModErr initialize_modules(helper_context& context);
int load_single(helper_context& context, const char* id);
module::SdkVersion sdk_version();
}
