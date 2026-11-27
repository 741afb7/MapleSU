// MapleSU runtime log implementation and current keyless entry points.
//
#include "kernel_module_kit_umbrella.h"

#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#include <filesystem>
#include <fstream>
#include <string>
#include <system_error>
#include <vector>

#include "runtime_log_private.h"
#include "compat/runtime_log_legacy.h"
#include "private_maple_runtime.h"
#include "maplesu_storage_worker_private.h"

namespace maplesu::runtime_log {
namespace {
using maplesu::sdk::fork_pipe_info;
using maplesu::sdk::fork_pipe_child_process;
using maplesu::sdk::write_errcode_from_child;
using maplesu::sdk::write_string_from_child;
using maplesu::sdk::write_int_from_child;
using maplesu::sdk::read_errcode_from_child;
using maplesu::sdk::read_string_from_child;
using maplesu::sdk::read_int_from_child;

static KModErr finish_log_worker(fork_pipe_info& pipe, KModErr code) {
    pipe.close_all();
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pipe.child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if ((waited != pipe.child_pid || !WIFEXITED(status) || WEXITSTATUS(status) != 0) && is_ok(code))
        return KModErr::ERR_READ_CHILD_ERRCODE;
    return code;
}

static_assert(static_cast<ssize_t>(KModErr::ERR_MODULE_ROOT_KEY) == -19997);
static_assert(static_cast<ssize_t>(KModErr::ERR_MODULE_FILE_NOT_EXIST) == -19936);
static_assert(static_cast<ssize_t>(KModErr::ERR_MODULE_CREATE_EMPTY_FILE) == -19935);
static_assert(static_cast<ssize_t>(KModErr::ERR_MODULE_READ_FILE) == -19932);
static_assert(static_cast<ssize_t>(KModErr::ERR_SET_FILE_SELINUX) == -19915);

// Logs use the kernel deployment directory, not the 17-byte system directory.
static KModErr get_autorun_bootstrap_path(std::string& path) {
    RETURN_IF_ERROR(storage::current_kernel_directory(path));
    path.append("init");
    return KModErr::OK;
}

template <typename Operation>
static KModErr run_log_worker(Operation&& operation) {
    try {
        storage::WorkerScope worker;
        const KModErr error = worker.root();
        if (is_failed(error)) return error;
        const auto result = operation();
        const auto closed = worker.close_checked();
        return is_failed(result) ? result : closed;
    } catch (...) {
        return KModErr::ERR_READ_CHILD_ERRCODE;
    }
}

// WorkerScope has released its storage selection and session before these frames.
template <typename Payload>
[[noreturn]] static void send_log_worker(fork_pipe_info& pipe, KModErr code, Payload&& payload) {
    bool sent = false;
    try {
        const bool wrote_code = write_errcode_from_child(pipe, code);
        const bool wrote_payload = payload();
        sent = wrote_code && wrote_payload;
    } catch (...) {}
    pipe.close_all();
    _exit(sent ? 0 : 1);
}

template <typename Payload>
static KModErr receive_log_worker(fork_pipe_info& pipe, Payload&& payload, KModErr payload_error) {
    if (pipe.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;
    KModErr code = KModErr::OK;
    try {
        if (!read_errcode_from_child(pipe, code)) code = KModErr::ERR_READ_CHILD_ERRCODE;
        else if (!payload() && is_ok(code)) code = payload_error;
    } catch (...) {
        (void)finish_log_worker(pipe, KModErr::ERR_READ_CHILD_ERRCODE);
        throw;
    }
    return finish_log_worker(pipe, code);
}

KModErr read(void (*on_log_text)(const char*)) {
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    if (on_log_text == nullptr) return KModErr::ERR_MODULE_PARAM;

    std::string log_text;
    fork_pipe_info pipe_info;
    if (fork_pipe_child_process(pipe_info)) {
        const KModErr code = run_log_worker([&] {
            std::string bootstrap;
            RETURN_IF_ERROR(get_autorun_bootstrap_path(bootstrap));
            const std::string log_path = bootstrap + ".log";
            std::error_code error;
            const bool exists = file_utils::file_exists(std::filesystem::path(log_path), error);
            if (error) return KModErr::ERR_MODULE_READ_FILE;
            if (!exists) return KModErr::ERR_MODULE_FILE_NOT_EXIST;
            if (!file_utils::read_text_file(log_path.c_str(), log_text)) return KModErr::ERR_MODULE_READ_FILE;
            RunFlagFile safe_flag(bootstrap + ".safe_flag");
            if (safe_flag.read() == 3) {
                std::string crash_text;
                (void)file_utils::read_text_file((log_path + ".crash").c_str(), crash_text);
                if (!crash_text.empty()) {
                    log_text.append("=========================\n");
                    log_text.append("\tLast Crash Log\n");
                    log_text.append("=========================\n");
                    log_text.append(crash_text);
                }
            }
            return KModErr::OK;
        });
        send_log_worker(pipe_info, code, [&] { return write_string_from_child(pipe_info, log_text); });
    }

    const KModErr code = receive_log_worker(pipe_info,
        [&] { return read_string_from_child(pipe_info, log_text); }, KModErr::ERR_READ_CHILD_STRING);
    if (is_ok(code)) on_log_text(log_text.c_str());
    return code;
}

KModErr set_enabled(bool enable) {
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    fork_pipe_info pipe_info;
    if (fork_pipe_child_process(pipe_info)) {
        const KModErr code = run_log_worker([&] {
            std::string bootstrap;
            RETURN_IF_ERROR(get_autorun_bootstrap_path(bootstrap));
            const std::string flag_path = bootstrap + ".log.enable";
            if (enable) {
                if (!file_utils::create_empty_file(flag_path.c_str()))
                    return KModErr::ERR_MODULE_CREATE_EMPTY_FILE;
                if (chmod(flag_path.c_str(), 0777) != 0 ||
                    setxattr(flag_path.c_str(), "security.selinux", "u:object_r:kernel:s0", 21, 0) != 0)
                    return KModErr::ERR_SET_FILE_SELINUX;
            } else {
                std::error_code error;
                (void)std::filesystem::remove_all(std::filesystem::path(flag_path), error);
                if (error) return KModErr::ERR_MODULE_DEL_FILE;
            }
            sync();
            return KModErr::OK;
        });
        send_log_worker(pipe_info, code, [] { return true; });
    }
    return receive_log_worker(pipe_info, [] { return true; }, KModErr::OK);
}
}  // namespace

KModErr query_enabled(bool& enabled) {
    enabled = false;
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    fork_pipe_info pipe_info;
    if (fork_pipe_child_process(pipe_info)) {
        int child_enabled = 0;
        const KModErr code = run_log_worker([&] {
            std::string bootstrap;
            RETURN_IF_ERROR(get_autorun_bootstrap_path(bootstrap));
            std::error_code error;
            const bool exists = file_utils::file_exists(std::filesystem::path(bootstrap + ".log.enable"), error);
            if (error) return KModErr::ERR_MODULE_READ_FILE;
            child_enabled = exists ? 1 : 0;
            return KModErr::OK;
        });
        send_log_worker(pipe_info, code, [&] { return write_int_from_child(pipe_info, child_enabled); });
    }

    int reported_enabled = 0;
    const KModErr code = receive_log_worker(pipe_info,
        [&] { return read_int_from_child(pipe_info, reported_enabled); }, KModErr::ERR_READ_CHILD_INT);
    if (is_ok(code)) {
        if (reported_enabled != 0 && reported_enabled != 1) return KModErr::ERR_READ_CHILD_INT;
        enabled = reported_enabled == 1;
    }
    return code;
}

namespace {
KModErr clear() {
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    fork_pipe_info pipe_info;
    if (fork_pipe_child_process(pipe_info)) {
        const KModErr code = run_log_worker([&] {
            std::string bootstrap;
            RETURN_IF_ERROR(get_autorun_bootstrap_path(bootstrap));
            const std::string log_path = bootstrap + ".log";
            std::error_code error;
            const bool exists = file_utils::file_exists(std::filesystem::path(log_path), error);
            if (error) return KModErr::ERR_MODULE_READ_FILE;
            if (!exists) return KModErr::ERR_MODULE_FILE_NOT_EXIST;
            return file_utils::create_empty_file(log_path.c_str())
                ? KModErr::OK : KModErr::ERR_MODULE_CREATE_EMPTY_FILE;
        });
        send_log_worker(pipe_info, code, [] { return true; });
    }
    return receive_log_worker(pipe_info, [] { return true; }, KModErr::OK);
}
}  // namespace
}  // namespace maplesu::runtime_log

// These keyless names and signatures are the current SDK boundary.
namespace maplesu::sdk {
KModErr read_maplesu_log_cb(void (*on_log_text)(const char*)) {
    return maplesu::runtime_log::read(on_log_text);
}
KModErr set_maplesu_log_enabled(bool enable) {
    return maplesu::runtime_log::set_enabled(enable);
}
bool is_maplesu_log_enabled() {
    // Keep the legacy bool projection; structured consumers use query_enabled.
    bool enabled = false;
    return is_ok(maplesu::runtime_log::query_enabled(enabled)) && enabled;
}
KModErr clear_maplesu_log() {
    return maplesu::runtime_log::clear();
}
}  // namespace maplesu::sdk

namespace skroot_env {
KModErr read_skroot_log_cb(void (*callback)(const char*)) {
    return maplesu::sdk::read_maplesu_log_cb(callback);
}
KModErr set_skroot_log_enabled(bool enable) {
    return maplesu::sdk::set_maplesu_log_enabled(enable);
}
bool is_skroot_log_enabled() {
    return maplesu::sdk::is_maplesu_log_enabled();
}
KModErr clear_skroot_log() {
    return maplesu::sdk::clear_maplesu_log();
}
}  // namespace skroot_env
