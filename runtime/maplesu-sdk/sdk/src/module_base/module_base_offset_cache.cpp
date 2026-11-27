// module base offset cache implementation.
//
#include "kernel_module_kit_umbrella.h"

#include <array>
#include "maplesu_request_identity.h"
#include <cstring>
#include <new>
#include <string>
#include <unordered_map>
#include <unistd.h>

extern "C" const char* __get_current_module_id32_1b0494ae8a788541db46b82cc1f0577c();

namespace kernel_module {
namespace {
constexpr size_t kIdentityBytes = 1024;
struct OffsetCache {
    pid_t process = 0;
    std::array<char, kIdentityBytes> module{};
    RequestIdentity identity;
    std::unordered_map<std::string, uint32_t> values;
};
thread_local OffsetCache cache;

bool select_current_scope() {
    const char* module = __get_current_module_id32_1b0494ae8a788541db46b82cc1f0577c();
    const size_t module_size = module ? strnlen(module, kIdentityBytes) : 0;
    if (!module_size || module_size == kIdentityBytes) {
        cache.values.clear();
        cache.process = 0;
        return false;
    }
    const auto process = getpid();
    if (cache.process != process ||
        std::memcmp(cache.module.data(), module, module_size + 1) ||
        !cache.identity.matches()) {
        cache.values.clear();
        std::memcpy(cache.module.data(), module, module_size + 1);
        if (!cache.identity.capture()) return false;
        cache.process = process;
    }
    return true;
}
}

// Only offsets discovered in this thread/process and runtime scope are reused.
// Old unscoped files are not read or written; caching is optional.
KModErr put_offset_cache(const char* key, uint32_t value) {
    if (!key || !*key) return KModErr::ERR_MODULE_PARAM;
    if (!select_current_scope()) return KModErr::OK;
    try {
        cache.values[std::string(key)] = value;
    } catch (const std::bad_alloc&) {
        cache.values.clear();
    }
    return KModErr::OK;
}

KModErr get_offset_cache(const char* key, uint32_t& out) {
    if (!key || !*key) return KModErr::ERR_MODULE_PARAM;
    if (!select_current_scope()) return KModErr::ERR_MODULE_OFFSET_CACHE_NOT_FOUND;
    try {
        const auto found = cache.values.find(std::string(key));
        if (found == cache.values.end()) return KModErr::ERR_MODULE_OFFSET_CACHE_NOT_FOUND;
        out = found->second;
        return KModErr::OK;
    } catch (const std::bad_alloc&) {
        return KModErr::ERR_MODULE_OFFSET_CACHE_NOT_FOUND;
    }
}
}  // namespace kernel_module
