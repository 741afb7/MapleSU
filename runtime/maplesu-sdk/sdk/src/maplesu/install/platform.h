
//
#pragma once

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <new>
#include <string>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

#include "core.h"
#include "executable_publish.h"

namespace maplesu::install::platform {

// --- production implementations -------------------------------------------
inline bool write_file_publish(const char* path, const unsigned char* data,
                               unsigned long size, const char* selinux_ctx) {
    return maplesu_publish_executable(path, data, size, selinux_ctx) == KModErr::OK;
}

// (`std::ios::binary | std::ios::out` == libc++ 4|16 == 20).
inline bool write_file_direct(const char* path, const unsigned char* data, unsigned long size) {
    std::ofstream out(path, std::ios::binary | std::ios::out);
    if (!out.is_open()) return false;
    out.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(size));
    out.close();
    return out.good();
}

inline bool create_empty_file(const char* path) {
    std::ofstream out(path, std::ios::binary | std::ios::out | std::ios::trunc);
    if (!out.is_open()) return false;
    out.close();
    return out.good();
}

inline KModErr preserve_or_initialize_log(const char* path, const char* initial) {
    int fd = ::open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    const bool created = fd >= 0;
    if (fd < 0) {
        if (errno != EEXIST) return KModErr::ERR_MODULE_OPEN_FILE;
        fd = ::open(path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    }
    const auto finish = [&](KModErr result, int error) {
        if (::close(fd) != 0 && result == KModErr::OK) {
            result = created ? KModErr::ERR_MODULE_WRITE_FILE : KModErr::ERR_MODULE_READ_FILE;
            error = errno;
        }
        errno = error;
        return result;
    };
    struct stat info {};
    if (::fstat(fd, &info) != 0) return finish(KModErr::ERR_MODULE_READ_FILE, errno);
    if (!S_ISREG(info.st_mode)) return finish(KModErr::ERR_MODULE_OPEN_FILE, EINVAL);
    if (info.st_uid != ::geteuid()) return finish(KModErr::ERR_MODULE_OPEN_FILE, EPERM);
    if (info.st_nlink != 1) return finish(KModErr::ERR_MODULE_OPEN_FILE, EMLINK);
    // Existing logs, including empty ones, belong to the previous attempt.
    // Keep their inode and bytes while an in-flight helper may still append.
    if (!created) return finish(KModErr::OK, 0);
    std::size_t offset = 0;
    const std::size_t size = std::strlen(initial);
    while (offset < size) {
        const ssize_t count = ::write(fd, initial + offset, size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return finish(KModErr::ERR_MODULE_WRITE_FILE, count < 0 ? errno : EIO);
        offset += static_cast<std::size_t>(count);
    }
    if (::fchmod(fd, core::kExecMode0777) != 0 ||
        ::fsetxattr(fd, core::kSelinuxXattr, core::kSelinuxKernel,
                    sizeof(core::kSelinuxKernel), 0) != 0)
        return finish(KModErr::ERR_SET_FILE_SELINUX, errno);
    return finish(KModErr::OK, 0);
}

inline bool set_file_selinux_access_mode(const char* path, const char* context) {
    if (::chmod(path, core::kExecMode0777) != 0) return false;
    if (context == nullptr) return false;
    const std::size_t n = std::strlen(context) + 1;
    return ::setxattr(path, core::kSelinuxXattr, context, n, 0) == 0;
}

inline bool file_exists(const char* path) {
    struct stat st {};
    return ::stat(path, &st) == 0;
}

inline bool rename_path(const char* from, const char* to) { return ::rename(from, to) == 0; }

inline bool delete_path(const char* path) {
    if (::unlink(path) == 0) return true;
    return ::rmdir(path) == 0;
}

inline bool make_dir(const char* path) {
    if (::mkdir(path, core::kExecMode0777) == 0) return true;
    return errno == EEXIST;
}

inline int last_errno() { return errno; }

}  // namespace maplesu::install::platform
