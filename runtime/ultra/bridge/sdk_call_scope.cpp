#include "sdk_call_scope.h"
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <initializer_list>
#include <mutex>
#include <new>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>


extern "C" pid_t __real_fork();
extern "C" int __real_pipe2(int*, int);
extern "C" pid_t __real_waitpid(pid_t, int*, int);
extern "C" int __real_close(int);
extern "C" __attribute__((weak, visibility("hidden"))) void maplesu_hook_fork_prepare() noexcept;
extern "C" __attribute__((weak, visibility("hidden"))) void maplesu_hook_fork_complete() noexcept;

namespace {
thread_local maplesu::SdkCallContext* current_call = nullptr;
void record_process_error(int error) {
    if (current_call && !current_call->process_error) current_call->process_error = error;
}
enum Resource : unsigned {
    Environment = 1u << 0, Authorization = 1u << 1, Modules = 1u << 2,
    Policy = 1u << 3, Settings = 1u << 4, Log = 1u << 5,
    Execution = 1u << 6, WebUi = 1u << 7, Vendor = 1u << 8, Parasite = 1u << 9,
    All = (1u << 10) - 1,
};
constexpr unsigned resource_count = 10;
struct CallPolicy {
    maplesu::SdkCallAccess access;
    unsigned reads = 0, writes = 0;
    bool owned_transport = true;
};
bool named(const char* operation, std::initializer_list<const char*> names) {
    if (!operation) return false;
    for (const char* name : names) if (std::strcmp(operation, name) == 0) return true;
    return false;
}
CallPolicy call_policy(const char* operation) {
    using Access = maplesu::SdkCallAccess;
    if (named(operation, {"probe_manager_owner_root", "read_installed_helper"}))
        return {Access::Isolated};
    if (named(operation, {"get_sdk_version", "get_sdk_build_identity", "get_system_status",
                          "query_manager_owner_identity"}))
        return {Access::Local};
    if (named(operation, {"install_maplesu_environment", "uninstall_maplesu_environment", "restart_zygote64"}))
        return {Access::Exclusive, 0, All};
    if (named(operation, {"get_maplesu_environment_state", "get_installed_maplesu_environment_version",
                          "parse_module_desc_from_zip_file"}))
        return {Access::Read, Environment};
    if (named(operation, {"get_root_status_report", "run_root_cmd"}))
        // Each command owns its worker, credentials and output. Shell side effects
        // are not a process-global SDK transaction or an overview prerequisite.
        return {Access::Read, Environment | Execution};
    if (named(operation, {"get_su_auth_list", "get_su_uid_profiles"}))
        return {Access::Read, Environment | Authorization};
    if (named(operation, {"add_su_auth_list", "refresh_su_auth_list", "remove_su_auth_list",
                          "clear_su_auth_list", "set_su_uid_profile"}))
        return {Access::Exclusive, Environment, Authorization};
    if (named(operation, {"get_all_modules_list", "get_module_control_capabilities", "get_module_control"}))
        return {Access::Read, Environment | Modules};
    if (named(operation, {"install_module", "install_module_dev_run_once", "uninstall_module", "set_module_enabled"}))
        return {Access::Exclusive, Environment, Modules | Policy | WebUi};
    if (named(operation, {"get_module_policy_settings"}))
        return {Access::Read, Environment | Policy};
    if (named(operation, {"publish_policy_backend", "set_module_policy_settings"}))
        return {Access::Exclusive, Environment | Modules, Policy};
    if (named(operation, {"start_module_web_ui_server_async"}))
        return {Access::Exclusive, Environment | Modules, WebUi};
    if (named(operation, {"is_boot_fail_protect_enabled", "is_adb_forced_disabled"}))
        return {Access::Read, Environment | Settings};
    if (named(operation, {"set_boot_fail_protect_enabled", "set_adb_forced_disabled"}))
        return {Access::Exclusive, Environment, Settings};
    if (named(operation, {"is_maplesu_log_enabled", "read_maplesu_log"}))
        return {Access::Read, Environment | Log};
    if (named(operation, {"set_maplesu_log_enabled", "clear_maplesu_log"}))
        return {Access::Exclusive, Environment, Log};
    if (named(operation, {"oneplus_bypass_is_work_normal"}))
        return {Access::Read, Environment | Vendor};
    if (named(operation, {"oneplus_bypass_stage1_and_2"}))
        return {Access::Exclusive, Environment, Vendor};
    if (named(operation, {"parasite_precheck_app"}))
        return {Access::Exclusive, Environment, Parasite};
    if (named(operation, {"parasite_implant_app", "parasite_implant_su_env"}))
        return {Access::Exclusive, Environment, Parasite};
    // Diagnostic item names include kernel writes; retain conservative exclusion.
    if (named(operation, {"test_maplesu_basics", "test_maplesu_default_module"}))
        return {Access::Exclusive, 0, All};
    return {Access::Exclusive, 0, All, false};
}
struct Admission {
    std::mutex mutex;
    std::condition_variable changed;
    unsigned readers[resource_count]{};
    unsigned writers_waiting[resource_count]{};
    unsigned writers = 0;
};
Admission& admission() { static Admission value; return value; }
struct QueryPipe {
    int input = -1, output = -1;
    maplesu::SdkCallContext* owner = nullptr;
};
struct QueryPipes {
    std::mutex mutex;
    std::vector<QueryPipe> entries;
};
QueryPipes& query_pipes() { static QueryPipes value; return value; }
bool transport_parent() { return current_call && current_call->owner_pid == getpid(); }
}

namespace maplesu {
SdkCallAccess sdk_call_access(const char* operation) { return call_policy(operation).access; }

SdkCallScope::SdkCallScope(const char* operation) {
    const auto policy = call_policy(operation);
    access_ = policy.access;
    read_mask_ = policy.reads & ~policy.writes;
    write_mask_ = policy.writes;
    owned_transport_ = policy.owned_transport;
    context_.owns_transport = owned_transport_;
    context_.owner_pid = getpid();
    if (access_ == SdkCallAccess::Local) { acquired_ = true; return; }
    if (access_ == SdkCallAccess::Isolated) {
        context_.retry_interrupted_wait = false;
    } else {
        auto& state = admission();
        std::unique_lock lock(state.mutex);
        for (unsigned i = 0; i < resource_count; ++i)
            if (write_mask_ & (1u << i)) ++state.writers_waiting[i];
        acquired_ = state.changed.wait_for(lock, std::chrono::milliseconds(1500), [&] {
            if (state.writers & (read_mask_ | write_mask_)) return false;
            for (unsigned i = 0; i < resource_count; ++i) {
                if ((write_mask_ & (1u << i)) && state.readers[i]) return false;
                if ((read_mask_ & (1u << i)) && state.writers_waiting[i]) return false;
            }
            return true;
        });
        for (unsigned i = 0; i < resource_count; ++i)
            if (write_mask_ & (1u << i)) --state.writers_waiting[i];
        if (!acquired_) { state.changed.notify_all(); return; }
        for (unsigned i = 0; i < resource_count; ++i)
            if (read_mask_ & (1u << i)) ++state.readers[i];
        state.writers |= write_mask_;
    }
    previous_call_ = current_call;
    current_call = &context_;
    acquired_ = true;
}

SdkCallScope::~SdkCallScope() {
    if (!acquired_ || access_ == SdkCallAccess::Local) return;
    // SDK workers normally call _exit. Never unwind into copied admission/JNI.
    if (context_.owner_pid != getpid()) _exit(125);
    {
        auto& pipes = query_pipes();
        std::lock_guard lock(pipes.mutex);
        for (auto& entry : pipes.entries) if (entry.owner == &context_) {
            // Only unknown legacy calls can retain asynchronous FD ownership.
            if (owned_transport_) {
                if (entry.input >= 0) __real_close(entry.input);
                if (entry.output >= 0) __real_close(entry.output);
            }
            entry = {};
        }
    }
    current_call = previous_call_;
    if (access_ == SdkCallAccess::Isolated) return;
    auto& state = admission();
    {
        std::lock_guard lock(state.mutex);
        for (unsigned i = 0; i < resource_count; ++i)
            if (read_mask_ & (1u << i)) --state.readers[i];
        state.writers &= ~write_mask_;
    }
    state.changed.notify_all();
}
}

// These are link-time wrappers inside this bridge only. Failed SDK setup leaves
// child_pid at zero; a group wait would otherwise consume another query's child.
extern "C" pid_t __wrap_fork() {
    // Publication precedes the pipe registry, including the real atfork callbacks.
    struct HookForkGuard {
        HookForkGuard() { if (maplesu_hook_fork_prepare) maplesu_hook_fork_prepare(); }
        ~HookForkGuard() { if (maplesu_hook_fork_complete) maplesu_hook_fork_complete(); }
    } hook_fork_guard;
    if (!transport_parent()) {
        const pid_t result = __real_fork();
        if (result < 0) record_process_error(errno);
        return result;
    }
    auto& pipes = query_pipes();
    // Keep this FD table stable across fork. Children release this same short lock,
    // then use only their own transport; they never touch the parent's registry.
    std::unique_lock lock(pipes.mutex);
    const pid_t result = __real_fork();
    if (result == 0) {
        for (auto& entry : pipes.entries) {
            if (entry.owner && entry.owner != current_call) {
                if (entry.input >= 0) __real_close(entry.input);
                if (entry.output >= 0) __real_close(entry.output);
            }
            entry = {};
        }
    } else if (result < 0) record_process_error(errno);
    return result;
}
extern "C" int __wrap_pipe2(int* fds, int flags) {
    if (!transport_parent()) {
        const int result = __real_pipe2(fds, flags);
        if (result < 0) record_process_error(errno);
        return result;
    }
    auto& pipes = query_pipes();
    std::lock_guard lock(pipes.mutex);
    QueryPipe* available = nullptr;
    for (auto& entry : pipes.entries) if (!entry.owner) {
        available = &entry;
        break;
    }
    if (!available) {
        try {
            pipes.entries.emplace_back();
            available = &pipes.entries.back();
        } catch (const std::bad_alloc&) {
            record_process_error(ENOMEM);
            errno = ENOMEM;
            return -1;
        }
    }
    const int result = __real_pipe2(fds, flags);
    if (result == 0) *available = {fds[0], fds[1], current_call};
    else record_process_error(errno);
    return result;
}
extern "C" int __wrap_close(int fd) {
    if (!transport_parent()) return __real_close(fd);
    auto& pipes = query_pipes();
    std::lock_guard lock(pipes.mutex);
    for (auto& entry : pipes.entries) {
        if (entry.input == fd) entry.input = -1;
        if (entry.output == fd) entry.output = -1;
        if (entry.input < 0 && entry.output < 0) entry.owner = nullptr;
    }
    return __real_close(fd);
}
extern "C" pid_t __wrap_waitpid(pid_t child, int* status, int flags) {
    if (!current_call) return __real_waitpid(child, status, flags);
    // Reads only wait for their own child. Preserve legacy group waits in
    // exclusive calls unless failed setup left the SDK's child PID at zero.
    if (child <= 0) {
        if (!current_call->owns_transport && !current_call->process_error) return __real_waitpid(child, status, flags);
        record_process_error(ECHILD);
        errno = ECHILD;
        return -1;
    }
    pid_t result;
    // Isolated workers own their deadline and finite EINTR retry budget.
    do { result = __real_waitpid(child, status, flags); } while (
        result < 0 && errno == EINTR && current_call->retry_interrupted_wait);
    if (result < 0 && (errno != EINTR || current_call->retry_interrupted_wait)) record_process_error(errno);
    return result;
}
