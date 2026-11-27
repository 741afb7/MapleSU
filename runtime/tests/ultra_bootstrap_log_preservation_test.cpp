#include "module_err_def.h"
#include "maplesu_storage_private.h"
#include "../maplesu-sdk/sdk/src/maplesu/install/bootstrap_install.h"
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

namespace fs = std::filesystem;
namespace {
std::string directory, focus, injection;
int focused_fd = -1;
std::vector<std::string> resets;
std::string read(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), {}};
}
void put(const std::string& path, const std::string& value) {
    std::ofstream out(path, std::ios::binary);
    out.write(value.data(), value.size());
    if (!out.good()) throw std::runtime_error("fixture write");
}
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
}

namespace default_modules::module_root {
KModErr set_current_root(bool) { return KModErr::OK; }
}
namespace maplesu::storage {
KModErr current_kernel_directory(std::string& out) { out = directory; return KModErr::OK; }
}

extern "C" int __real_open(const char*, int, ...);
extern "C" int __wrap_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags); mode = va_arg(args, mode_t); va_end(args);
    }
    if (focus == path && (injection == "EACCES" || injection == "EIO")) {
        errno = injection == "EACCES" ? EACCES : EIO; return -1;
    }
    const int fd = __real_open(path, flags, mode);
    if (focus == path && fd >= 0) focused_fd = fd;
    return fd;
}
extern "C" int __real_fstat(int, struct stat*);
extern "C" int __wrap_fstat(int fd, struct stat* out) {
    if (fd == focused_fd && injection == "stat_EIO") { errno = EIO; return -1; }
    const int result = __real_fstat(fd, out);
    if (!result && fd == focused_fd && injection == "owner") out->st_uid = geteuid() + 1;
    return result;
}
extern "C" ssize_t __real_write(int, const void*, size_t);
extern "C" ssize_t __wrap_write(int fd, const void* value, size_t size) {
    if (fd == focused_fd && injection == "write_zero") return 0;
    if (fd == focused_fd && injection == "write_ENOSPC") { errno = ENOSPC; return -1; }
    return __real_write(fd, value, size);
}
extern "C" int __real_fsetxattr(int, const char*, const void*, size_t, int);
extern "C" int __wrap_fsetxattr(int fd, const char*, const void* value, size_t size, int flags) {
    return __real_fsetxattr(fd, "user.maplesu.selinux", value, size, flags);
}
extern "C" int __real_setxattr(const char*, const char*, const void*, size_t, int);
extern "C" int __wrap_setxattr(const char* path, const char*, const void* value, size_t size, int flags) {
    return __real_setxattr(path, "user.maplesu.selinux", value, size, flags);
}
extern "C" int __real_renameat(int, const char*, int, const char*);
extern "C" int __wrap_renameat(int from, const char* source, int to, const char* target) {
    if (std::string(target) == "init.boot_session.duplicate" || std::string(target) == "init.safe_flag")
        resets.emplace_back(target);
    return __real_renameat(from, source, to, target);
}

int main() {
    const std::string base = "/tmp/maplesu-bootstrap-logs-" + std::to_string(getpid());
    const std::vector<std::string> cases = {"content", "empty", "missing", "symlink", "directory",
        "fifo", "hardlink", "owner", "EACCES", "EIO", "stat_EIO", "write_zero", "write_ENOSPC"};
    unsigned passed = 0;
    for (const auto& name : cases) {
        try {
            directory = base + "/" + name + "/";
            fs::create_directories(directory);
            const std::string helper = directory + "init";
            const std::string evidence("failure\0boot-session", 20);
            for (const char* suffix : {".log", ".log.last", ".log.crash"})
                if (name != "missing" && name != "write_zero" && name != "write_ENOSPC")
                    put(helper + suffix, name == "empty" ? "" : evidence);
            put(helper + ".running.lock", "lock");
            put(helper + ".boot_session.work", "old-work");
            put(helper + ".boot_session.completed", "old-completed");
            put(helper + ".safe_flag", "3");
            put(helper + ".boot_session.duplicate", "old-boot");
            const int lock = __real_open((helper + ".running.lock").c_str(), O_RDWR);
            require(lock >= 0 && !flock(lock, LOCK_EX | LOCK_NB), "fixture lock");
            struct stat before{}; require(!__real_fstat(lock, &before), "fixture stat");
            const std::string victim = directory + "victim";
            put(victim, evidence);
            if (name == "symlink" || name == "directory" || name == "fifo" || name == "hardlink") {
                fs::remove(helper + ".log");
                if (name == "symlink") fs::create_symlink(victim, helper + ".log");
                if (name == "directory") fs::create_directory(helper + ".log");
                if (name == "fifo") require(!mkfifo((helper + ".log").c_str(), 0600), "fixture fifo");
                if (name == "hardlink") fs::create_hard_link(victim, helper + ".log");
            }
            // Keep an old implementation's FIFO open from hanging the red run.
            const int fifo = name == "fifo" ? __real_open((helper + ".log").c_str(), O_RDWR | O_NONBLOCK) : -1;
            const int writer = name == "content" ? __real_open((helper + ".log").c_str(), O_WRONLY | O_APPEND) : -1;
            require(name != "content" || writer >= 0, "fixture append descriptor");
            injection = name; focus = helper + ".log"; focused_fd = -1; resets.clear();
            const auto result = maplesu::install::prepare_environment_bootstrap_state();
            const int error = errno;
            injection.clear(); focused_fd = -1;
            struct stat after{}; require(!::stat((helper + ".running.lock").c_str(), &after), "lock path");
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino, "lock inode replaced");
            const int contender = __real_open((helper + ".running.lock").c_str(), O_RDWR);
            require(contender >= 0 && flock(contender, LOCK_EX | LOCK_NB) < 0 &&
                (errno == EWOULDBLOCK || errno == EAGAIN), "in-flight lock lost");
            close(contender); close(lock); if (fifo >= 0) close(fifo);
            const bool success = name == "content" || name == "empty" || name == "missing";
            if (success) {
                require(result == KModErr::OK, "preparation failed");
                for (const char* suffix : {".log", ".log.last", ".log.crash"}) {
                    const auto expected = name == "content" ? evidence : name == "missing" &&
                        std::string(suffix) == ".log" ? "[FATAL] autorun_bootstrap: NEVER EXECUTED" : "";
                    require(read(helper + suffix) == expected, "existing log bytes changed or missing log not initialized");
                }
                require(read(helper + ".boot_session.work").empty() &&
                    read(helper + ".boot_session.completed").empty(), "state reset changed");
                require(resets == std::vector<std::string>{"init.boot_session.duplicate", "init.safe_flag"}, "reset order");
                if (writer >= 0) {
                    require(__real_write(writer, ":in-flight", 10) == 10 &&
                        read(helper + ".log") == evidence + ":in-flight", "in-flight log descriptor detached");
                    close(writer);
                }
            } else {
                require(result != KModErr::OK && resets.empty(), "failed log admission reached safe reset");
                const int expected = name == "EACCES" ? EACCES : name == "EIO" || name == "stat_EIO" ||
                    name == "write_zero" ? EIO : name == "write_ENOSPC" ? ENOSPC : name == "symlink" ? ELOOP :
                    name == "hardlink" ? EMLINK : name == "owner" ? EPERM : EINVAL;
                require(error == expected, "original errno lost");
                require(read(victim) == evidence, "linked victim changed");
                require(read(helper + ".safe_flag") == "3" &&
                    read(helper + ".boot_session.duplicate") == "old-boot", "failure protection changed");
            }
            std::printf("PASS %s\n", name.c_str()); ++passed;
        } catch (const std::exception& error) {
            injection.clear(); focused_fd = -1;
            std::printf("FAIL %s: %s\n", name.c_str(), error.what());
        }
    }
    fs::remove_all(base);
    std::printf("BOOTSTRAP_LOG_PRESERVATION passed=%u total=%zu actual_production_tus=3 selinux=fixture identity=fixture\n", passed, cases.size());
    return passed == cases.size() ? 0 : 1;
}
