#include "module_policy_management.h"
#include "module_policy_config.h"
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"
#include "maplesu_storage_private.h"
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdexcept>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

static const char* scenario;
static pid_t session_process;
static unsigned forks = 0;
static bool interrupted = false;
static unsigned requested = 1;
static bool is(const char* value) { return strcmp(scenario, value) == 0; }

namespace default_modules::module_root {
KModErr set_current_root(bool owner) {
    assert(owner == !is("helper_success"));
    if (is("root_error")) return KModErr::ERR_MODULE_ROOT_KEY;
    session_process = getpid();
    return KModErr::OK;
}
}
namespace kernel_module {
KModErr maplesu_request_worker_admission() { return is("admission_error") ? KModErr::ERR_MODULE_ROOT_KEY : KModErr::OK; }
uint64_t maplesu_request_auth_state() {
    if (is("invalid_auth")) return 0;
    if (is("exhausted_auth")) return UINT64_MAX;
    return is("helper_success") ? 3 : 1;
}
bool maplesu_request_owner_binding_matches(const RequestOwnerBinding& binding) {
    return binding.process == uint64_t(getpid()) && session_process == getpid();
}
bool maplesu_request_owner_lifetime_capture(RequestOwnerLifetimeToken&) { return false; }
KModErr maplesu_request_owner_close() {
    const bool active = session_process == getpid();
    session_process = 0;
    return active && (is("close_error") || is("store_close_error")) ? KModErr::ERR_MODULE_ROOT_KEY : KModErr::OK;
}
KModErr maplesu_request_helper_open() { session_process = getpid(); return KModErr::OK; }
KModErr maplesu_request_owner_deployment(RequestDeploymentNamespace& out) {
    assert(session_process == getpid());
    memcpy(out.record.name, "0123456789abcdef", 16);
    out.binding.process = getpid();
    out.binding.actual_uid = getuid();
    return KModErr::OK;
}
CleanupReceiptScope::CleanupReceiptScope() noexcept : saved_errno_(errno), saved_process_(getpid()), saved_reply_{} {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept { errno = saved_errno_; }
}
namespace maplesu::storage {
StorageScope::StorageScope(const StorageLocator& locator, const char* module_id) {
    assert(!module_id && !strcmp(locator.kernel_component, "0123456789abcdef"));
    error_ = KModErr::OK;
}
StorageScope::~StorageScope() {}
}
// The real IPC path runs; only the SDK and policy store are fixtures.
namespace maplesu::module_policy {
Store::Store(int directory) : directory_(directory) {}
Store::~Store() {}
Store Store::open(bool create) {
    if (is("store_error") || is("store_close_error")) throw std::runtime_error("MODULE_POLICY_CONFIG_TEST");
    assert(create == (strncmp(scenario, "save_", 5) == 0));
    return Store(-1);
}
void Store::request(Mode mode, const std::string& boot, bool allow_unverified) {
    assert(boot == "fixture-boot" && allow_unverified == is("save_7"));
    requested = static_cast<unsigned>(mode);
}
State Store::read(const std::string& boot) const {
    assert(boot == "fixture-boot");
    return {static_cast<Mode>(requested), BootState{Mode::Hide, 42, boot}, true, true, true};
}
std::string current_boot_id() { return "fixture-boot"; }
bool valid_mode(unsigned mode) { return mode == 0 || mode == 1 || mode == 3; }
}

extern "C" int __real_pipe2(int*, int);
extern "C" int __wrap_pipe2(int* fds, int flags) {
    assert(flags == O_CLOEXEC);
    if (is("pipe_error")) { errno = EMFILE; return -1; }
    return __real_pipe2(fds, flags);
}
extern "C" pid_t __real_fork();
extern "C" pid_t __wrap_fork() {
    ++forks;
    if (is("fork_error")) { errno = EAGAIN; return -1; }
    return __real_fork();
}
extern "C" int __real_poll(pollfd*, nfds_t, int);
extern "C" int __wrap_poll(pollfd* fds, nfds_t count, int timeout) {
    assert(count == 1 && timeout == 3000);
    if (is("poll_timeout")) return 0;
    if (is("poll_error")) { errno = EINVAL; return -1; }
    if (is("poll_eintr") && !interrupted) { interrupted = true; errno = EINTR; return -1; }
    return __real_poll(fds, count, timeout);
}
extern "C" ssize_t __real_read(int, void*, size_t);
extern "C" ssize_t __wrap_read(int fd, void* data, size_t size) {
    if (is("read_error")) { errno = EIO; return -1; }
    auto result = __real_read(fd, data, size);
    if (is("read_short")) return result > 0 ? result - 1 : result;
    if (is("read_eof")) return 0;
    return result;
}
extern "C" pid_t __real_waitpid(pid_t, int*, int);
extern "C" pid_t __wrap_waitpid(pid_t child, int* status, int flags) {
    assert(child > 0 && flags == 0);
    if (is("wait_eintr") && !interrupted) { interrupted = true; errno = EINTR; return -1; }
    const auto result = __real_waitpid(child, status, flags);
    if (is("wait_error")) { errno = ECHILD; return -1; }
    return is("wait_mismatch") && result > 0 ? result + 1 : result;
}
extern "C" [[noreturn]] void __real__exit(int);
extern "C" [[noreturn]] void __wrap__exit(int status) {
    if (is("child_signal")) raise(SIGTERM);
    __real__exit(is("child_exit") ? 17 : status);
}

int main(int argc, char** argv) {
    assert(argc == 2);
    scenario = argv[1];
    int request = -1;
    if (is("invalid_request")) request = 2;
    if (strncmp(scenario, "save_", 5) == 0) request = scenario[5] - '0';
    const auto result = maplesu::module_policy::manage(request);
    std::string expected;
    if (is("invalid_auth") || is("exhausted_auth") || is("invalid_request")) expected = "MODULE_POLICY_INVALID:0";
    else if (is("pipe_error")) expected = "MODULE_POLICY_PIPE:" + std::to_string(EMFILE);
    else if (is("fork_error")) expected = "MODULE_POLICY_FORK:" + std::to_string(EAGAIN);
    else if (is("poll_timeout")) expected = "MODULE_POLICY_POLL_TIMEOUT:3000";
    else if (is("poll_error")) expected = "MODULE_POLICY_POLL:" + std::to_string(EINVAL);
    else if (is("read_error")) expected = "MODULE_POLICY_READ:" + std::to_string(EIO);
    else if (is("read_short")) expected = "MODULE_POLICY_READ_SHORT:" + std::to_string(sizeof(result) - 1);
    else if (is("read_eof")) expected = "MODULE_POLICY_READ_SHORT:0";
    else if (is("wait_error")) expected = "MODULE_POLICY_WAIT:" + std::to_string(ECHILD);
    else if (is("wait_mismatch")) expected = "MODULE_POLICY_WAIT:";
    else if (is("child_signal")) expected = "MODULE_POLICY_CHILD_SIGNAL:" + std::to_string(SIGTERM);
    else if (is("child_exit")) expected = "MODULE_POLICY_CHILD_EXIT:17";
    else if (is("root_error") || is("admission_error")) expected = "MODULE_POLICY_ROOT:" + std::to_string(static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY));
    else if (is("close_error")) expected = "MODULE_POLICY_CLOSE:" + std::to_string(static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY));
    else if (is("store_error") || is("store_close_error")) expected = "MODULE_POLICY_CONFIG_TEST";
    if (!expected.empty()) {
        const bool matches = is("wait_mismatch") ? strncmp(result.reason, expected.c_str(), expected.size()) == 0 : result.reason == expected;
        if (!matches || result.error != ((is("root_error") || is("admission_error") || is("close_error")) ? static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY) : -1)) {
            fprintf(stderr, "FAIL %s: error=%lld reason=%s expected=%s\n", scenario, static_cast<long long>(result.error), result.reason, expected.c_str());
            return 1;
        }
        assert(!strchr(result.reason, '/'));
    } else {
        assert(result.error == 0 && result.reason[0] == '\0');
        assert(result.requested == static_cast<unsigned>(request < 0 ? 1 : request == 7 ? 3 : request));
        assert(result.effective == 3 && result.capabilities == 42);
        assert(result.unverified_hide && result.can_try_unverified && result.hide_failed);
    }
    assert(forks == ((is("invalid_auth") || is("exhausted_auth") || is("invalid_request") || is("pipe_error") || is("admission_error")) ? 0u : 1u));
    printf("PASS module policy management %s\n", scenario);
}
