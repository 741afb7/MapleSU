#include "backend_publish.h"
#include "../upgrade/executable_publish.h"
#include "maplesu_sdk/maplesu_root.h"
#include "../../maplesu-sdk/sdk/include/maplesu_request_private.h"
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#ifndef MAPLESU_POLICY_DIRECTORY
#define MAPLESU_POLICY_DIRECTORY "/data"
#endif

namespace maplesu::module_policy {
namespace {
struct Fd {
    int value;
    ~Fd() { if (value >= 0) close(value); }
};
bool matches(int dir, const std::string& name, const unsigned char* bytes, size_t size) {
    Fd file{openat(dir, name.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)};
    struct stat st{};
    if (file.value < 0 || fstat(file.value, &st) || !S_ISREG(st.st_mode) || st.st_uid ||
        st.st_nlink != 1 || (st.st_mode & 07777) != 0700 || st.st_size != static_cast<off_t>(size)) return false;
    char context[64];
    constexpr char expected[] = "u:object_r:system_file:s0";
    if (fgetxattr(file.value, "security.selinux", context, sizeof(context)) != sizeof(expected) ||
        memcmp(context, expected, sizeof(expected))) return false;
    unsigned char buffer[16384];
    size_t offset = 0;
    while (offset < size) {
        const auto count = read(file.value, buffer, std::min(size - offset, sizeof(buffer)));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || memcmp(buffer, bytes + offset, static_cast<size_t>(count))) return false;
        offset += static_cast<size_t>(count);
    }
    return true;
}
int64_t execute(const char* digest, const unsigned char* bytes, size_t size) {
    auto root = maplesu::sdk::get_root();
    if (is_failed(root)) return static_cast<int64_t>(root);
    if (getuid() || geteuid()) return static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY);
    Fd dir{open(MAPLESU_POLICY_DIRECTORY, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (dir.value < 0) return static_cast<int64_t>(KModErr::ERR_MODULE_OPEN_DIR);
    const char* parts[] = {"adb", "maplesu", "bin"};
    for (unsigned i = 0; i < 3; ++i) {
        if (i && mkdirat(dir.value, parts[i], i == 1 ? 0700 : 0755) && errno != EEXIST)
            return static_cast<int64_t>(KModErr::ERR_MODULE_OPEN_DIR);
        int next = openat(dir.value, parts[i], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        struct stat st{};
        if (next < 0) return static_cast<int64_t>(KModErr::ERR_MODULE_OPEN_DIR);
        if (fstat(next, &st) || st.st_uid || (st.st_mode & 0022)) {
            close(next);
            return static_cast<int64_t>(KModErr::ERR_MODULE_OPEN_DIR);
        }
        close(dir.value);
        dir.value = next;
    }
    const auto name = std::string("libmaplesu_policy_boot.") + digest + ".so";
    if (matches(dir.value, name, bytes, size)) return 0;
    auto result = maplesu_publish_private_backend_at(dir.value, name.c_str(), bytes, size, "u:object_r:system_file:s0");
    if (is_failed(result)) return static_cast<int64_t>(result);
    return matches(dir.value, name, bytes, size) ? 0 : static_cast<int64_t>(KModErr::ERR_MODULE_WRITE_FILE);
}
}
int64_t publish_backend(const char* digest, const unsigned char* bytes, size_t size) {
    constexpr auto invalid = static_cast<int64_t>(KModErr::ERR_MODULE_PARAM);
    constexpr auto io_error = static_cast<int64_t>(KModErr::ERR_MODULE_WRITE_FILE);
    const auto auth = kernel_module::maplesu_request_auth_state();
    if (!kernel_module::maplesu_request_auth_usable(auth) ||
        kernel_module::maplesu_request_auth_helper(auth) ||
        !digest || strnlen(digest, 65) != 64 || !bytes || size < 64 || size > 32 * 1024 * 1024) return invalid;
    for (unsigned i = 0; i < 64; ++i)
        if (!((digest[i] >= '0' && digest[i] <= '9') || (digest[i] >= 'a' && digest[i] <= 'f'))) return invalid;
    if (memcmp(bytes, "\177ELF\2\1", 6) || bytes[16] != 3 || bytes[17] || bytes[18] != 183 || bytes[19]) return invalid;
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) return static_cast<int64_t>(admission);
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC)) return io_error;
    const pid_t child = fork();
    if (child < 0) { close(pipes[0]); close(pipes[1]); return io_error; }
    if (!child) {
        close(pipes[0]);
        int64_t result = io_error;
        try {
            const auto selected = kernel_module::maplesu_request_auth_legacy(auth) ? KModErr::OK :
                kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::ManagerOwner);
            result = static_cast<int64_t>(selected);
            if (!is_failed(selected)) result = execute(digest, bytes, size);
        } catch (...) { result = io_error; }
        if (!kernel_module::maplesu_request_auth_legacy(auth)) {
            kernel_module::CleanupReceiptScope diagnostic;
            try {
                const auto closed = kernel_module::maplesu_request_owner_close();
                if (!result && is_failed(closed)) result = static_cast<int64_t>(closed);
            } catch (...) { if (!result) result = io_error; }
        }
        ssize_t sent;
        do { sent = write(pipes[1], &result, sizeof(result)); } while (sent < 0 && errno == EINTR);
        _exit(sent == sizeof(result) ? 0 : 1);
    }
    close(pipes[1]);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    pollfd watch{pipes[0], POLLIN, 0};
    int64_t result = io_error;
    int ready;
    do {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (left <= 0) { ready = 0; break; }
        ready = poll(&watch, 1, static_cast<int>(left));
    } while (ready < 0 && errno == EINTR);
    ssize_t received = -1;
    if (ready > 0) do { received = read(pipes[0], &result, sizeof(result)); } while (received < 0 && errno == EINTR);
    close(pipes[0]);
    if (received != sizeof(result)) kill(child, SIGKILL);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    return received == sizeof(result) && waited == child && WIFEXITED(status) && !WEXITSTATUS(status) ? result : io_error;
}
}
