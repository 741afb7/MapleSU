// Module identity is non-secret; authority belongs to the calling task.
#include <cerrno>
#include <cstring>
#include <pthread.h>
#include "maplesu_request_private.h"

extern "C" __attribute__((weak, visibility("hidden"))) void maplesu_hook_fork_prepare() noexcept;
extern "C" __attribute__((weak, visibility("hidden"))) void maplesu_hook_fork_complete() noexcept;
namespace {
char module_id32[1024] = "GEfQdKmhZSkuWJI2rs21JO39Xmp8XcZg";
// Only explicitly selected historical clients consume this thread's credential.
thread_local char legacy_credential[1024] = {};
int identity_fork_error = EAGAIN;
void before_fork() { if (maplesu_hook_fork_prepare) maplesu_hook_fork_prepare(); }
void after_fork() { if (maplesu_hook_fork_complete) maplesu_hook_fork_complete(); }
__attribute__((constructor)) void initialize_identity() {
    identity_fork_error = pthread_atfork(before_fork, after_fork, after_fork);
}
}
extern "C" void __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(
    const char* credential) {
    char candidate[sizeof(legacy_credential)] = {};
    if (credential) std::strncpy(candidate, credential, sizeof(candidate) - 1);
    std::memcpy(legacy_credential, candidate, sizeof(candidate));
}
extern "C" const char* __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW() {
    return legacy_credential;
}
namespace kernel_module {
KModErr maplesu_request_select_legacy(const char* credential) {
    const size_t length = credential ? strnlen(credential, sizeof(legacy_credential)) : 0;
    if (length < 16 || length >= sizeof(legacy_credential))
        return KModErr::ERR_MODULE_ROOT_KEY;
    // Copy first because the supplied value may alias this thread's getter.
    char candidate[sizeof(legacy_credential)] = {};
    std::memcpy(candidate, credential, length);
    const auto error = maplesu_request_set_auth_mode(RequestAuthMode::LegacyKey);
    if (is_failed(error)) return error;
    std::memcpy(legacy_credential, candidate, sizeof(candidate));
    return KModErr::OK;
}
void maplesu_request_clear_legacy() {
    volatile char* bytes = legacy_credential;
    for (size_t i = 0; i < sizeof(legacy_credential); ++i) bytes[i] = 0;
}
}
extern "C" void __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(const char* id32) {
    if (!id32) return;
    std::strncpy(module_id32, id32, sizeof(module_id32) - 1);
    module_id32[sizeof(module_id32) - 1] = 0;
}
extern "C" const char* __get_current_module_id32_1b0494ae8a788541db46b82cc1f0577c() { return module_id32; }
extern "C" __attribute__((visibility("hidden"))) int maplesu_identity_fork_status() noexcept {
    return identity_fork_error;
}
