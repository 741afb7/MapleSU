#include "../maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp"
#include "../maplesu-sdk/sdk/src/maplesu/compat/fork_legacy_abi.h"

#include <cstdio>
#include <sys/wait.h>
#include <thread>

namespace maplesu::sdk { bool fork_pipe_child_process(fork_pipe_info&); }

static pid_t parent;
static thread_local int child_registrations;
extern "C" int __real___cxa_thread_atexit(void (*)(void*), void*, void*);
extern "C" int __wrap___cxa_thread_atexit(void (*destroy)(void*), void* object, void* dso) {
    if (parent && getpid() != parent) {
        ++child_registrations;
        return 0;
    }
    return __real___cxa_thread_atexit(destroy, object, dso);
}

static int run_child(bool inherited) {
    maplesu::sdk::fork_pipe_info pipe;
    if (maplesu::sdk::fork_pipe_child_process(pipe)) {
        const auto selected = kernel_module::maplesu_request_set_auth_mode(
            kernel_module::RequestAuthMode::ManagerOwner);
        if (selected != KModErr::OK) _exit(2);
        if (inherited && (kernel_module::owner_management.session ||
            kernel_module::owner_management.uncertain ||
            !kernel_module::owner_management.allocations.empty() ||
            kernel_module::management_reply.completed || kernel_module::owner_channel.ready)) _exit(3);
        dprintf(1, "child TLS destructor registrations=%d inherited=%d\n", child_registrations, inherited);
        _exit(child_registrations ? 1 : 0);
    }
    if (pipe.child_pid <= 0) return 4;
    int status = 0;
    if (waitpid(pipe.child_pid, &status, 0) != pipe.child_pid) return 5;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 6;
}

int main() {
    parent = getpid();
    for (int worker = 0; worker < 10; ++worker) {
        int error = 0;
        std::thread cold([&] {
            for (int repeat = 0; repeat < 3 && !error; ++repeat) error = run_child(false);
        });
        cold.join();
        if (error) return error;
    }
    // Synthetic local bookkeeping only: the child must drop it, never close the
    // parent's session, and preparation must not change the parent's live view.
    kernel_module::management_process = parent;
    kernel_module::owner_management.session = 41;
    kernel_module::owner_management.uncertain = true;
    kernel_module::owner_management.allocations.push_back({0x1234, 29, 4096});
    kernel_module::management_reply.completed = true;
    kernel_module::owner_channel.ready = true;
    if (const int error = run_child(true)) return error;
    if (kernel_module::owner_management.session != 41 ||
        !kernel_module::owner_management.uncertain ||
        kernel_module::owner_management.allocations.size() != 1 ||
        !kernel_module::management_reply.completed || !kernel_module::owner_channel.ready) return 7;
    puts("30 cold-thread forks + inherited session reset passed");
}
