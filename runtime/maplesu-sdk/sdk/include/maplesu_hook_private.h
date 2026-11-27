#pragma once

#include "maplesu_request_private.h"
#include "module_base_kernel_func_hook.h"

namespace kernel_module {
// Explicit LegacyKey may select the checked v2 boot compatibility provider.
__attribute__((visibility("hidden"))) KModErr maplesu_hook_legacy_boot_selected(bool& selected);
// Hook closure storage follows the explicitly selected authenticated backend.
__attribute__((visibility("hidden"))) KModErr maplesu_hook_reserve_code(
    uint32_t bytes, uint64_t near_anchor, uint64_t& address);
__attribute__((visibility("hidden"))) KModErr maplesu_hook_write(
    uint64_t address, const void* data, uint32_t bytes);
__attribute__((visibility("hidden"))) KModErr maplesu_hook_release(uint64_t address);
__attribute__((visibility("hidden"))) RequestManagementReply maplesu_hook_reply();
__attribute__((visibility("hidden"))) KModErr maplesu_hook_close();
class HookCleanupReceiptScope {
public:
    HookCleanupReceiptScope() noexcept;
    ~HookCleanupReceiptScope() noexcept;
    HookCleanupReceiptScope(const HookCleanupReceiptScope&) = delete;
    HookCleanupReceiptScope& operator=(const HookCleanupReceiptScope&) = delete;
private:
    CleanupReceiptScope owner_receipt_;
    RequestManagementReply legacy_reply_{};
    uint64_t process_ = 0;
    int saved_errno_ = 0;
};
__attribute__((visibility("hidden"))) KModErr maplesu_hook_shared_bool_create(
    bool initial, RequestSharedBool& object);
__attribute__((visibility("hidden"))) KModErr maplesu_hook_shared_bool_store(
    const RequestSharedBool& object, bool value);
__attribute__((visibility("hidden"))) KModErr maplesu_hook_shared_bool_release(
    const RequestSharedBool& object);
// One generated HBT actor, still unreachable and owned by the caller. PREPARE
// transfers the allocation into the same publication as its iterate_dir gate.
struct OwnedHookActor {
    uint64_t allocation = 0, target = 0;
    uint32_t bytes = 0, kcfi = 0;
};
__attribute__((visibility("hidden"))) KModErr maplesu_install_owned_hook_with_actor(
    uint64_t address, const void* instructions, uint32_t size,
    const OwnedHookActor& actor, bool& transferred, HookHandle* output);
// Explicit shared dependencies join the same publication before its entry is
// exposed. Addresses embedded in callback bytes never imply owned references.
__attribute__((visibility("hidden"))) KModErr maplesu_install_owned_hook_with_shared_bools(
    uint64_t address, const void* instructions, uint32_t size, bool after,
    const RequestSharedBool* dependencies, uint32_t count, HookHandle* output);
}
