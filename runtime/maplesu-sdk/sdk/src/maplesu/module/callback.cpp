
//
#include "../compat/module_callback_legacy_abi.h"
#include "../compat/module_runtime_legacy_abi.h"
#include "process_context.h"
#include "maplesu_storage_private.h"
#include "maplesu_module_environment_private.h"
#include "module_base_kernel_request.h"

#include <algorithm>
#include <chrono>
#include <climits>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <memory>
#include <random>
#include <sched.h>
#include <string>
#include <sys/prctl.h>
#include <vector>


class RedirectStdToFile {
public:
    enum class OpenMode { Truncate, Append };
    static RedirectStdToFile& instance() {
        static RedirectStdToFile inst;
        return inst;
    }
    bool redirect(const std::string& path, OpenMode mode) {
        if (redirected_) return true;
        fflush(nullptr);
        stdout_fd_ = dup(STDOUT_FILENO);
        stderr_fd_ = dup(STDERR_FILENO);
        if (stdout_fd_ < 0 || stderr_fd_ < 0) {
            if (stdout_fd_ >= 0) { close(stdout_fd_); stdout_fd_ = -1; }
            if (stderr_fd_ >= 0) { close(stderr_fd_); stderr_fd_ = -1; }
            return false;
        }
        file_fd_ = open(path.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC |
                        (mode == OpenMode::Truncate ? O_TRUNC : O_APPEND), 0644);
        if (file_fd_ < 0) {
            dup2(stdout_fd_, STDOUT_FILENO);
            dup2(stderr_fd_, STDERR_FILENO);
            close(stdout_fd_); stdout_fd_ = -1;
            close(stderr_fd_); stderr_fd_ = -1;
            return false;
        }
        if (dup2(file_fd_, STDOUT_FILENO) < 0 || dup2(file_fd_, STDERR_FILENO) < 0) {
            close(file_fd_); file_fd_ = -1;
            dup2(stdout_fd_, STDOUT_FILENO);
            dup2(stderr_fd_, STDERR_FILENO);
            close(stdout_fd_); stdout_fd_ = -1;
            close(stderr_fd_); stderr_fd_ = -1;
            return false;
        }
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        redirected_ = true;
        return true;
    }
    bool redirected() const noexcept { return redirected_; }
    bool restore() {
        if (!redirected_) return true;
        fflush(nullptr);
        bool ok = true;
        if (stdout_fd_ >= 0) {
            ok = dup2(stdout_fd_, STDOUT_FILENO) >= 0;
            close(stdout_fd_); stdout_fd_ = -1;
        }
        if (stderr_fd_ >= 0) {
            ok = (dup2(stderr_fd_, STDERR_FILENO) >= 0) && ok;
            close(stderr_fd_); stderr_fd_ = -1;
        }
        if (file_fd_ >= 0) { close(file_fd_); file_fd_ = -1; }
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        redirected_ = false;
        return ok;
    }
    ~RedirectStdToFile() { restore(); }
private:
    int stdout_fd_ = -1;
    int stderr_fd_ = -1;
    int file_fd_ = -1;
    bool redirected_ = false;
};
static_assert(sizeof(RedirectStdToFile) == 16 && alignof(RedirectStdToFile) == 4);

namespace random_utils {
namespace detail {
inline std::mt19937& mt19937_32() {
    static thread_local std::mt19937 gen(std::random_device{}());
    return gen;
}
inline std::mt19937_64& mt19937_64() {
    static thread_local std::mt19937_64 gen(std::chrono::steady_clock::now().time_since_epoch().count());
    return gen;
}
}
inline uint32_t random_u32_from_i32() {
    static thread_local std::uniform_int_distribution<int32_t> dist(INT32_MIN, INT32_MAX);
    return static_cast<uint32_t>(dist(detail::mt19937_32()));
}
inline uint32_t random_u32_from_i32(uint32_t a, uint32_t b) {
    const uint32_t lo = std::min(a, b), hi = std::max(a, b);
    if (lo == hi) return hi;
    const uint64_t range = static_cast<uint64_t>(hi) - lo + 1;
    constexpr uint64_t full_range = uint64_t{1} << 32;
    if (range == full_range) return random_u32_from_i32();
    const uint64_t limit = full_range - full_range % range;
    uint32_t value;
    do { value = random_u32_from_i32(); } while (value >= limit);
    return lo + static_cast<uint32_t>(value % range);
}
inline std::string generate_random_str_with_dot(size_t size) {
    if (!size) return {};
    static constexpr char alphabet[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.";
    static thread_local std::uniform_int_distribution<size_t> dist(0, sizeof(alphabet) - 2);
    std::string result;
    result.reserve(size);
    auto& generator = detail::mt19937_64();
    for (size_t i = 0; i < size; ++i) result.push_back(alphabet[dist(generator)]);
    return result;
}
}

namespace maplesu::sdk::features::callback {
namespace {

static KModErr bootstrap_log_path(std::string& out) {
    std::string kernel_dir;
    RETURN_IF_ERROR(storage::current_kernel_directory(kernel_dir));
    out = (std::filesystem::path(kernel_dir) / "init.log").string();
    return KModErr::OK;
}

static KModErr module_paths(const std::string& module_id32,
                           std::string& elf_path, std::string& private_dir) {
    std::string system_dir;
    RETURN_IF_ERROR(storage::current_system_directory(system_dir));
    private_dir = (std::filesystem::path(system_dir) / "modules" / module_id32 / "").string();
    elf_path = module_id32.empty() ? std::string{} :
        (std::filesystem::path(private_dir) / module_id32).string();
    return KModErr::OK;
}

class ScopedLogRedirect {
public:
    explicit ScopedLogRedirect(const std::string& path) {
        auto& redirect = RedirectStdToFile::instance();
        restore_ = !redirect.redirected() &&
            redirect.redirect(path, RedirectStdToFile::OpenMode::Append);
    }
    ~ScopedLogRedirect() {
        if (restore_) RedirectStdToFile::instance().restore();
    }
    DISABLE_COPY_MOVE(ScopedLogRedirect);
private:
    bool restore_ = false;
};
}
}

namespace file_utils {
static bool read_file_bytes(const char* path, std::vector<uint8_t>& bytes) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) return false;
    stream.seekg(0, std::ios::end);
    const std::streamoff size = stream.tellg();
    if (size < 0) return false;
    stream.seekg(0, std::ios::beg);
    bytes.resize(static_cast<size_t>(size));
    return static_cast<bool>(stream.read(reinterpret_cast<char*>(bytes.data()), size));
}
}

namespace env_path_util {
namespace detail {
// 去掉路径尾部多余的 '/'（保留根 "/" 本身）。
static std::string trim_trailing_slashes(std::string text) {
    while (text.size() > 1 && text.back() == '/') text.pop_back();
    return text;
}
}  // namespace detail
}  // namespace env_path_util

static void reset_module_process_env(const std::string& module_private_dir) {
    if (setsid() < 0) setpgid(0, 0);
    signal(SIGPIPE, SIG_IGN);
    int mount_namespace = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
    if (mount_namespace >= 0) {
        const int joined = setns(mount_namespace, CLONE_NEWNS);
        close(mount_namespace);
        if (joined >= 0) unshare(CLONE_NEWNS);
    }
    cg_v2::migrate_self_to_root();
    cg_v1::migrate_self_threads_v1();
    selinux_procattr::setcon("u:r:shell:s0");
    selinux_procattr::setexeccon("u:r:shell:s0");
    std::string process_name = random_utils::generate_random_str_with_dot(random_utils::random_u32_from_i32(1, 15));
    prctl(PR_SET_NAME, process_name.c_str(), 0, 0, 0);
    if (!module_private_dir.empty()) {
        const char* old_path = getenv("PATH");
        std::string path = old_path ? old_path : "";
        const std::string trimmed = env_path_util::detail::trim_trailing_slashes(module_private_dir);
        if (!trimmed.empty()) {
            bool found = false;
            for (size_t start = 0; start <= path.size();) {
                size_t end = path.find(':', start);
                if (end == std::string::npos) end = path.size();
                if (path.substr(start, end - start) == trimmed) {
                    found = true;
                    break;
                }
                start = end + 1;
            }
            if (!found) {
                std::string combined;
                if (path.empty()) combined = trimmed;
                else combined = trimmed + ":" + path;
                setenv("PATH", combined.c_str(), 1);
            }
        }
    }
    chdir(module_private_dir.c_str());
}

namespace maplesu::sdk::features::callback {
namespace {
static KModErr invoke_install(const std::string& module_id32, std::string& out_reason) {
    std::string elf_path;
    std::string private_dir;
    RETURN_IF_ERROR(module_paths(module_id32, elf_path, private_dir));
    std::vector<uint8_t> bytes;
    if (!file_utils::read_file_bytes(elf_path.c_str(), bytes)) return KModErr::ERR_MODULE_READ_FILE;
    if (!compat::module_has_install_callback(bytes)) return KModErr::OK;
    module_environment::Environment environment;
    const auto version = get_sdk_version();
    RETURN_IF_ERROR(module_environment::capture(version.major, version.minor, version.patch,
                                               environ, environment));
    reset_module_process_env(private_dir);
    RETURN_IF_ERROR(environment.apply());
    std::unique_ptr<void, decltype(&dlclose)> handle(dlopen(elf_path.c_str(), RTLD_NOW), dlclose);
    if (!handle) {
        if (const char* error = dlerror()) fprintf(stderr, "dlopen: %s, failed: %s\n", elf_path.c_str(), error);
        return KModErr::ERR_MODULE_DLOPEN;
    }
    auto inject = reinterpret_cast<void (*)()>(dlsym(handle.get(), compat::native_module::kModuleIdentitySymbol));
    const auto install = compat::native_module::resolve_export(
        [&handle](const char* symbol) { return dlsym(handle.get(), symbol); },
        compat::native_module::kModuleInstallSymbol, compat::native_module::kLegacyModuleInstallSymbol);
    if (!install) {
        if (const char* error = dlerror()) fprintf(stderr, "dlsym failed: %s\n", error);
        return KModErr::ERR_MODULE_DLSYM;
    }
    static thread_local std::string* tls_out = nullptr;
    struct RestoreOutput {
        std::string*& output;
        std::string* previous;
        ~RestoreOutput() { output = previous; }
    } restore_output{tls_out, tls_out};
    tls_out = &out_reason;
    if (inject) inject();
    RETURN_IF_ERROR(compat::invoke_module_install(install, kernel_module::current_module_root_key(),
        private_dir.c_str(), [](const char* reason) { if (tls_out) tls_out->assign(reason ? reason : ""); }));
    return out_reason.empty() ? KModErr::OK : KModErr::ERR_MODULE_REJECT_INSTALL;
}
}

KModErr invoke_module_on_install(const std::string& module_id32, std::string& out_reason) {
    std::string log_path;
    RETURN_IF_ERROR(bootstrap_log_path(log_path));
    ScopedLogRedirect redirect(log_path);
    return invoke_install(module_id32, out_reason);
}

KModErr invoke_module_on_uninstall(const std::string& module_id32) {
    std::string log_path;
    RETURN_IF_ERROR(bootstrap_log_path(log_path));
    ScopedLogRedirect redirect(log_path);
    std::string elf_path;
    std::string private_dir;
    RETURN_IF_ERROR(module_paths(module_id32, elf_path, private_dir));
    std::vector<uint8_t> bytes;
    if (!file_utils::read_file_bytes(elf_path.c_str(), bytes)) return KModErr::ERR_MODULE_READ_FILE;
    if (!compat::module_has_uninstall_callback(bytes)) return KModErr::OK;
    module_environment::Environment environment;
    const auto version = get_sdk_version();
    RETURN_IF_ERROR(module_environment::capture(version.major, version.minor, version.patch,
                                               environ, environment));
    reset_module_process_env(private_dir);
    RETURN_IF_ERROR(environment.apply());
    std::unique_ptr<void, decltype(&dlclose)> handle(dlopen(elf_path.c_str(), RTLD_NOW), dlclose);
    if (!handle) {
        if (const char* error = dlerror()) fprintf(stderr, "dlopen: %s, failed: %s\n", elf_path.c_str(), error);
        return KModErr::ERR_MODULE_DLOPEN;
    }
    auto inject = reinterpret_cast<void (*)()>(dlsym(handle.get(), compat::native_module::kModuleIdentitySymbol));
    const auto uninstall = compat::native_module::resolve_export(
        [&handle](const char* symbol) { return dlsym(handle.get(), symbol); },
        compat::native_module::kModuleUninstallSymbol, compat::native_module::kLegacyModuleUninstallSymbol);
    if (!uninstall) {
        if (const char* error = dlerror()) fprintf(stderr, "dlsym failed: %s\n", error);
        return KModErr::ERR_MODULE_DLSYM;
    }
    if (inject) inject();
    return compat::invoke_module_uninstall(uninstall, kernel_module::current_module_root_key(), private_dir.c_str());
}
}

#include "../compat/module_callback_exports_legacy_abi.h"
