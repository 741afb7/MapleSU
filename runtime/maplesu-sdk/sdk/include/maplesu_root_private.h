#pragma once
#include "maplesu_request_private.h"
#include <cstring>

namespace default_modules::module_root {
// Dedicated su process: retain its PTY/session and cancellation signals,
// while sharing the real SDK credential, namespace, cgroup and domain setup.
// Local diagnostics for one composite call; each transaction receipt is copied
// before later operations can replace the last wire response.
struct RootTransitionResult {
    KModErr credentials_error = KModErr::OK;
    KModErr error = KModErr::OK;
    kernel_module::RequestManagementReply credentials{};
    const char* phase = nullptr;
    int system_error = 0;
    int threads_total = 0, cpuset_moved = 0, stune_moved = 0, cgroup_error = 0;
    bool owner_mode = false;
    // The transport selector is independent of actual Manager identity.
    bool credentials_managed = false;
    bool credentials_attempted = false;
    // A COMMITTED effect remains authoritative even when the command reports
    // a post-commit error; later task/environment errors must not erase it.
    bool credentials_committed = false;
    bool environment_completed = false;
    KModErr task_state_error = KModErr::OK;
    kernel_module::RequestManagementReply task_state{};
    bool task_state_attempted = false;
};
inline const char* root_transition_phase_name(uint32_t code) noexcept {
    switch (code) {
    case 1: return "credentials";
    case 2: return "process_name";
    case 3: return "session";
    case 4: return "signal";
    case 5: return "namespace_open";
    case 6: return "namespace_stat";
    case 7: return "namespace_join";
    case 8: return "namespace_private";
    case 9: return "cgroup_v2";
    case 10: return "cgroup_v1";
    case 11: return "selinux_current";
    case 12: return "selinux_exec";
    case 13: return "complete";
    case 14: return "task_state";
    default: return nullptr;
    }
}
inline uint32_t root_transition_phase_code(const char* phase) noexcept {
    if (!phase) return 0;
    for (uint32_t code = 1; code <= 14; ++code)
        if (!std::strcmp(phase, root_transition_phase_name(code))) return code;
    return 0;
}
// Same worker and process only. Empty means no call in this execution context;
// environment completion does not prove continued kernel-session authority.
RootTransitionResult current_root_transition();
}
