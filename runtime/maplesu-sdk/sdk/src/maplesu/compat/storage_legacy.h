#pragma once

#include "../../../include/maplesu_storage_private.h"
#include "../../../include/maplesu_request_identity.h"
#include <cstring>
#include <utility>

extern "C" const char* __get_current_module_id32_1b0494ae8a788541db46b82cc1f0577c();

namespace maplesu::compat {

__attribute__((visibility("hidden")))
inline KModErr current_legacy_storage_context(storage::StorageContext& out) {
    if (!kernel_module::maplesu_request_auth_legacy(kernel_module::maplesu_request_auth_state()))
        return KModErr::ERR_MODULE_PARAM;
    kernel_module::RequestIdentity identity;
    if (!identity.capture() || !kernel_module::maplesu_request_auth_legacy(identity.auth_state))
        return KModErr::ERR_MODULE_ROOT_KEY;
    storage::StorageContext candidate;
    const char* module = __get_current_module_id32_1b0494ae8a788541db46b82cc1f0577c();
    const size_t module_length = module ? strnlen(module, 1024) : 0;
    if (module_length && module_length < 1024) candidate.module_id.assign(module, module_length);
    const size_t prefix_length = identity.length < 16 ? identity.length : 16;
    std::memcpy(candidate.locator.kernel_component, identity.key.data(), prefix_length);
    std::memcpy(candidate.locator.system_component, identity.key.data(), prefix_length);
    candidate.locator.system_component[prefix_length] = identity.key[0];
    if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    out = std::move(candidate);
    return KModErr::OK;
}

} // namespace maplesu::compat
