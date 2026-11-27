#include "module_environment_capture.h"
#include "../../maplesu-sdk/sdk/include/maplesu_module_environment_private.h"
#include "../../maplesu-sdk/sdk/include/maplesu_storage_worker_private.h"
#include "maplesu_sdk/maplesu_installer.h"
#include "../../src/module_task_environment.h"
#include <sys/wait.h>
#include <csignal>
#include <new>

namespace maplesu {
namespace {
struct Frame { int64_t error = 0; char facts[1024]{}; };
bool transfer(int fd, void* bytes, size_t size, bool sending) {
    char* cursor = static_cast<char*>(bytes);
    while (size) {
        const ssize_t count = sending ? write(fd, cursor, size) : read(fd, cursor, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { if (!count) errno = EPIPE; return false; }
        cursor += count; size -= static_cast<size_t>(count);
    }
    return true;
}
}
KModErr capture_module_environment(std::vector<std::string>& out) {
    out.clear();
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    const auto selected = kernel_module::maplesu_request_auth_mode(kernel_module::maplesu_request_auth_state());
    int pipe[2];
    if (pipe2(pipe, O_CLOEXEC)) return static_cast<KModErr>(-errno);
    const pid_t child = fork();
    if (child < 0) { const int error = errno; close(pipe[0]); close(pipe[1]); return static_cast<KModErr>(-error); }
    if (!child) {
        close(pipe[0]); signal(SIGPIPE, SIG_IGN);
        Frame frame;
        try {
            KModErr error = kernel_module::maplesu_request_set_auth_mode(selected);
            storage::WorkerScope worker;
            if (is_ok(error)) error = worker.root();
            module_environment::Environment environment;
            if (is_ok(error)) {
                const auto version = sdk::get_sdk_version();
                error = module_environment::capture(version.major, version.minor, version.patch, nullptr, environment);
            }
            if (is_ok(error)) {
                size_t used = 0;
                for (char* const* fact = environment.data(); *fact; ++fact) {
                    const size_t size = std::strlen(*fact);
                    if (used + size + 1 >= sizeof(frame.facts)) { error = static_cast<KModErr>(-E2BIG); break; }
                    std::memcpy(frame.facts + used, *fact, size); used += size;
                    frame.facts[used++] = '\n';
                }
            }
            const auto closed = worker.close_checked();
            if (is_ok(error)) error = closed;
            frame.error = static_cast<int64_t>(error);
        } catch (const std::bad_alloc&) { frame.error = static_cast<int64_t>(KModErr::ERR_NO_MEM); }
          catch (...) { frame.error = static_cast<int64_t>(KModErr::ERR_MODULE_TEST_CHANNEL); }
        const bool sent = transfer(pipe[1], &frame, sizeof(frame), true);
        close(pipe[1]); _exit(sent ? 0 : 1);
    }
    close(pipe[1]);
    Frame frame;
    const bool received = transfer(pipe[0], &frame, sizeof(frame), false);
    const int io_error = received ? 0 : (errno ? errno : EPIPE);
    close(pipe[0]);
    int status = 0; pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited < 0) return static_cast<KModErr>(-errno);
    if (!received) return static_cast<KModErr>(-io_error);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (frame.error) return static_cast<KModErr>(frame.error);
    if (!std::memchr(frame.facts, 0, sizeof(frame.facts))) return static_cast<KModErr>(-EPROTO);
    const std::string facts(frame.facts);
    std::vector<std::string> parsed;
    for (size_t start = 0; start < facts.size();) {
        const auto end = facts.find('\n', start);
        if (end == std::string::npos) return static_cast<KModErr>(-EPROTO);
        parsed.emplace_back(facts.substr(start, end - start)); start = end + 1;
    }
    module_task::Environment checked;
    if (checked.build(nullptr, parsed)) return static_cast<KModErr>(-EPROTO);
    out.swap(parsed);
    return KModErr::OK;
}
} // namespace maplesu
