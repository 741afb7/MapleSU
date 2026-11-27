#include "fixture_config.h"
#include "maplesu_storage_worker_private.h"
#include <cerrno>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>
#include <optional>

namespace {
uint64_t auth_state = 1, generation = 0;
pid_t session_process;
std::optional<maplesu::storage::WorkerScope> worker;
}

namespace fixture {
std::string deployment = "0123456789abcdef";
KModErr root_result = KModErr::OK, reset_result = KModErr::OK, install_result = KModErr::OK;
bool fail_chmod = false, fail_label = false, fail_close = false, fail_admission = false, label_contract_ok = true;
unsigned chmod_calls = 0, label_calls = 0, fork_calls = 0;
void select_deployment() {
    worker.reset();
    worker.emplace();
    if (is_failed(worker->root())) abort();
}
}
extern "C" KModErr maplesu_lock_module_root(bool) { return fixture::root_result; }
extern "C" [[noreturn]] void maplesu_module_worker_exit(int status) { _exit(status); }
namespace default_modules::module_root {
KModErr set_current_root(bool) {
    if (is_failed(fixture::root_result)) return fixture::root_result;
    if (session_process != getpid()) { session_process = getpid(); ++generation; }
    return KModErr::OK;
}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state() { return auth_state; }
KModErr maplesu_request_worker_admission() { return fixture::fail_admission ? KModErr::ERR_MODULE_ROOT_KEY : KModErr::OK; }
bool maplesu_request_owner_binding_matches(const RequestOwnerBinding& binding) {
    return binding.auth_state == auth_state && binding.process == uint64_t(getpid()) && session_process == getpid();
}
bool maplesu_request_owner_lifetime_capture(RequestOwnerLifetimeToken& token) {
    if (session_process != getpid()) return false;
    token.binding_.auth_state = auth_state;
    token.binding_.process = getpid();
    token.session_ = generation;
    return true;
}
bool maplesu_request_owner_lifetime_matches(const RequestOwnerLifetimeToken& token) {
    return token.session_ == generation && maplesu_request_owner_binding_matches(token.binding_);
}
KModErr maplesu_request_owner_close() {
    const bool active = session_process == getpid();
    session_process = 0;
    return active && fixture::fail_close ? KModErr::ERR_MODULE_ROOT_KEY : KModErr::OK;
}
KModErr maplesu_request_helper_open() { session_process = getpid(); ++generation; return KModErr::OK; }
KModErr maplesu_request_set_auth_mode(RequestAuthMode mode) { auth_state = ((auth_state & ~UINT64_C(3)) + 4) | static_cast<unsigned>(mode); return KModErr::OK; }
KModErr maplesu_request_owner_deployment(RequestDeploymentNamespace& out) {
    if (session_process != getpid() || fixture::deployment.size() != 16) return KModErr::ERR_MODULE_PARAM;
    memcpy(out.record.name, fixture::deployment.data(), 16);
    out.binding.auth_state = auth_state;
    out.binding.process = getpid();
    out.binding.actual_uid = getuid();
    return KModErr::OK;
}
CleanupReceiptScope::CleanupReceiptScope() noexcept : saved_errno_(errno), saved_process_(getpid()), saved_reply_{} {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept { errno = saved_errno_; }
}
extern "C" int __real_chmod(const char*, mode_t);
extern "C" int __wrap_chmod(const char* path, mode_t mode) {
    ++fixture::chmod_calls;
    if (fixture::fail_chmod) { errno = EACCES; return -1; }
    return __real_chmod(path, mode);
}
extern "C" int __wrap_setxattr(const char*, const char* name, const void* value, size_t size, int flags) {
    ++fixture::label_calls;
    const char* context = static_cast<const char*>(value);
    fixture::label_contract_ok &= !strcmp(name, "security.selinux") && flags == 0
        && size == strlen(context) + 1
        && (!strcmp(context, "u:object_r:kernel:s0") || !strcmp(context, "u:object_r:system_file:s0"));
    if (fixture::fail_label) { errno = EACCES; return -1; }
    return 0;  // Android file-label operation is the only simulated OS boundary.
}

extern "C" int __real_fchmod(int, mode_t);
extern "C" int __wrap_fchmod(int fd, mode_t mode) {
    ++fixture::chmod_calls;
    if (fixture::fail_chmod) { errno = EACCES; return -1; }
    return __real_fchmod(fd, mode);
}
extern "C" int __wrap_fsetxattr(int, const char* name, const void* value, size_t size, int flags) {
    return __wrap_setxattr(nullptr, name, value, size, flags);
}

extern "C" pid_t __real_fork();
extern "C" pid_t __wrap_fork() { ++fixture::fork_calls; return __real_fork(); }
