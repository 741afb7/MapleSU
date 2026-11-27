#include "module_policy_management.h"
#include "module_policy_config.h"
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"
#include "../../maplesu-sdk/sdk/include/maplesu_storage_worker_private.h"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <exception>
#include <poll.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>


namespace maplesu::module_policy {
namespace {
Reply failed(const char* stage, int64_t code) {
    Reply reply;
    snprintf(reply.reason, sizeof(reply.reason), "MODULE_POLICY_%s:%lld", stage, static_cast<long long>(code));
    return reply;
}
Reply execute(int request) {
    Reply reply;
    storage::WorkerScope worker;
    auto result = worker.root();
    if (is_failed(result)) {
        reply = failed("ROOT", static_cast<int64_t>(result));
        reply.error = static_cast<int64_t>(result);
        (void)worker.close_checked();
        return reply;
    }
    try {
        auto store = Store::open(request >= 0);
        auto boot = current_boot_id();
        if (request >= 0) store.request(static_cast<Mode>(request == 7 ? 3 : request), boot, request == 7);
        auto state = store.read(boot);
        reply.requested = static_cast<unsigned>(state.requested);
        reply.unverified_hide = state.unverified_hide;
        reply.can_try_unverified = state.can_try_unverified;
        reply.hide_failed = state.hide_failed;
        if (state.effective) {
            reply.effective = static_cast<int>(state.effective->mode);
            reply.capabilities = state.effective->capabilities;
        }
        reply.error = 0;
    } catch (const std::exception& e) {
        strncpy(reply.reason, e.what(), sizeof(reply.reason) - 1);
    }
    const auto closed = worker.close_checked();
    if (!reply.error && is_failed(closed)) {
        reply = failed("CLOSE", static_cast<int64_t>(closed));
        reply.error = static_cast<int64_t>(closed);
    }
    return reply;
}
}
Reply manage(int request) {
    Reply result;
    const auto auth = kernel_module::maplesu_request_auth_state();
    if (!kernel_module::maplesu_request_auth_usable(auth) ||
        (request != -1 && request != 7 && !valid_mode(static_cast<unsigned>(request)))) return failed("INVALID", 0);
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) {
        auto rejected = failed("ROOT", static_cast<int64_t>(admission));
        rejected.error = static_cast<int64_t>(admission);
        return rejected;
    }
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC)) return failed("PIPE", errno);
    pid_t pid = fork();
    if (pid < 0) {
        const int error = errno;
        close(pipes[0]); close(pipes[1]);
        return failed("FORK", error);
    }
    if (pid == 0) {
        close(pipes[0]);
        Reply reply = execute(request);
        const auto ignored = write(pipes[1], &reply, sizeof(reply)); (void)ignored;
        _exit(0);
    }
    close(pipes[1]);
    pollfd watch{pipes[0], POLLIN, 0};
    int ready;
    do { ready = poll(&watch, 1, 3000); } while (ready < 0 && errno == EINTR);
    Reply transport_error;
    ssize_t count = -1;
    if (ready > 0) {
        count = read(pipes[0], &result, sizeof(result));
        if (count != ssize_t(sizeof(result)))
            transport_error = failed(count < 0 ? "READ" : "READ_SHORT", count < 0 ? errno : count);
    } else {
        transport_error = failed(ready == 0 ? "POLL_TIMEOUT" : "POLL", ready == 0 ? 3000 : errno);
    }
    const bool received = count == ssize_t(sizeof(result));
    close(pipes[0]);
    if (!received) kill(pid, SIGKILL);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != pid) return failed("WAIT", waited < 0 ? errno : waited);
    if (!received) return transport_error;
    if (WIFSIGNALED(status)) return failed("CHILD_SIGNAL", WTERMSIG(status));
    if (!WIFEXITED(status)) return failed("CHILD_STATUS", status);
    if (WEXITSTATUS(status) != 0) return failed("CHILD_EXIT", WEXITSTATUS(status));
    return result;
}
}
