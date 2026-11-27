// private module tombstone purge implementation.
//
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>

#include <dirent.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <fcntl.h>
#include <unistd.h>

#include <chrono>
#include <functional>
#include <random>
#include <string>

#include "kernel_module_kit_umbrella.h"


namespace maplesu::sdk {

KModErr unsafe_create_hide_dir();

}  // namespace maplesu::sdk

// file_utils：目录为空判定（O_NOATIME 版本）。
namespace file_utils {

static bool is_dir_empty_noatime(const char* dir_path) {
    (void)dir_path;
    const int fd = open("/data/tombstones", O_RDONLY | O_DIRECTORY | O_NOATIME | O_CLOEXEC);
    if (fd < 0) {
        printf("open O_NOATIME failed: %s, errno=%d\n", "/data/tombstones", errno);
        return true;
    }
    DIR* dir = fdopendir(fd);
    if (dir == nullptr) {
        printf("fdopendir failed: %s, errno=%d\n", "/data/tombstones", errno);
        close(fd);
        return true;
    }
    bool empty = true;
    while (struct dirent* entry = readdir(dir)) {
        const char* name = entry->d_name;
        // 跳过 "." 与 ".."；其余任何条目都说明目录非空。
        if (name[0] == '.' && (name[1] == '\0' || (name[1] == '.' && name[2] == '\0'))) {
            continue;
        }
        empty = false;
        break;
    }
    closedir(dir);
    return empty;
}

}  // namespace file_utils

#include "maplesu_storage_private.h"
namespace maplesu::sdk {
static std::string get_kernel_file_dir_name() {
    maplesu::storage::StorageContext context;
    if (is_failed(maplesu::storage::current_context(context))) return {};
    return context.locator.kernel_component;
}
}

// dir_time_utils：按“分钟”级别比较目录时间戳。
//   struct DirTimesMinute = 3 x int64（atime/mtime/ctime 的 tv_sec/60）
//   get_dir_times_minute(path, out)  fstatat + S_ISDIR 检查 + 3 个 tv_sec/60
//   is_same_dir_times_minute(a, b)   比较 /data/tombstones 与 b 的三个分钟值
namespace dir_time_utils {

struct DirTimesMinute {
    int64_t atime_minute = 0;  // st_atim.tv_sec / 60
    int64_t mtime_minute = 0;  // st_mtim.tv_sec / 60
    int64_t ctime_minute = 0;  // st_ctim.tv_sec / 60
};

static bool get_dir_times_minute(const char* path, DirTimesMinute& out) {
    if (path == nullptr || path[0] == '\0') {
        puts("invalid path");
        return false;
    }
    struct stat st = {};
    if (fstatat(AT_FDCWD, path, &st, 0) != 0) {
        printf("fstatat failed: %s, errno=%d(%s)\n", path, errno, strerror(errno));
        return false;
    }
    if (!S_ISDIR(st.st_mode)) {
        printf("not a directory: %s\n", path);
        return false;
    }
    out.atime_minute = st.st_atim.tv_sec / 60;
    out.mtime_minute = st.st_mtim.tv_sec / 60;
    out.ctime_minute = st.st_ctim.tv_sec / 60;
    return true;
}

static bool is_same_dir_times_minute(const char* path, const char* /*unused_anchor*/) {
    DirTimesMinute tomb_times = {};
    DirTimesMinute anchor_times = {};
    if (!get_dir_times_minute("/data/tombstones", tomb_times)) {
        return false;
    }
    if (!get_dir_times_minute(path, anchor_times)) {
        return false;
    }
    return tomb_times.atime_minute == anchor_times.atime_minute &&
           tomb_times.mtime_minute == anchor_times.mtime_minute &&
           tomb_times.ctime_minute == anchor_times.ctime_minute;
}

}  // namespace dir_time_utils

// random_utils：两个线程本地随机源 + 工具函数。
//   random_u32_from_i32() uniform_int_distribution<int32>(INT32_MIN, INT32_MAX)
//   generate_random_str_with_dot(n)  字符表 = a-zA-Z0-9.，dist(0, 62)
namespace random_utils {

namespace detail {

inline std::mt19937& mt19937_32() {
    static thread_local std::mt19937 gen{std::random_device{}()};
    return gen;
}

inline std::mt19937_64& mt19937_64() {
    static thread_local std::mt19937_64 gen{static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count())};
    return gen;
}

}  // namespace detail

inline uint32_t random_u32_from_i32() {
    // 结果为 int32（负值也会出现，调用方用取模 % 10 使用）。
    static thread_local std::uniform_int_distribution<int32_t> dist(INT32_MIN, INT32_MAX);
    return static_cast<uint32_t>(dist(detail::mt19937_32()));
}

inline std::string generate_random_str_with_dot(size_t len) {
    if (len == 0) {
        return std::string();
    }
    static thread_local std::uniform_int_distribution<size_t> dist(0, 62);
    static const char alphabet[] =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.";
    std::string out;
    out.reserve(len);
    for (size_t index = 0; index < len; ++index) {
        out.push_back(alphabet[dist(detail::mt19937_64())]);
    }
    return out;
}

}  // namespace random_utils

// fd_guard：文件描述符 RAII。
//   reset(new_fd)  先 close 旧 fd（>=0 时），再接管 new_fd
//   ~fd_guard()    reset(-1)
struct fd_guard {
    int fd_ = -1;

    fd_guard() = default;
    explicit fd_guard(int fd) : fd_(fd) {}

    void reset(int new_fd) {
        if (fd_ >= 0) {
            close(fd_);
        }
        fd_ = new_fd;
    }

    int get() const { return fd_; }

    ~fd_guard() { reset(-1); }
};

// file_lock_utils::RunningLockFile：进程单实例文件锁。
//   init(path, mode)
//     close() 旧锁 -> 校验 path -> open(path, O_RDWR|O_CREAT|O_CLOEXEC, mode)
//     -> flock(LOCK_EX|LOCK_NB) -> fcntl(F_GETFD/F_SETFD, FD_CLOEXEC)
//   close()  flock(LOCK_UN) + close + fd_ = -1
namespace file_lock_utils {

class RunningLockFile {
public:
    RunningLockFile() = default;
    ~RunningLockFile() { close(); }

    bool init(const char* path, unsigned int mode) {
        close();
        if (path == nullptr || path[0] == '\0') {
            fwrite("Invalid lock file path\n", 23, 1, stderr);
            return false;
        }
        const int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, mode);
        if (fd < 0) {
            fprintf(stderr, "Unable to open lock file: %s, errno=%d (%s)\n",
                    path, errno, strerror(errno));
            return false;
        }
        if (flock(fd, LOCK_EX | LOCK_NB) < 0) {
            fprintf(stderr, "Another instance is running or lock failed: %s, errno=%d (%s)\n",
                    path, errno, strerror(errno));
            ::close(fd);
            return false;
        }
        const int flags = fcntl(fd, F_GETFD);
        if (flags > -1) {
            fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
        }
        fd_ = fd;
        return true;
    }

    void close() {
        if (fd_ > -1) {
            flock(fd_, LOCK_UN);
            ::close(fd_);
            fd_ = -1;
        }
    }

    int get() const { return fd_; }

private:
    int fd_ = -1;
};

}  // namespace file_lock_utils
