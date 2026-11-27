// Android platform binding for the boot helper.
//
// This is the only translation unit that calls libc/bionic directly.  It binds
// the syscall table used by the freestanding logic to real descriptors, real
// processes and the real dynamic loader.
#include "private/helper_core.h"

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

namespace {

int error_from_errno(void) {
    const int value = errno;
    return value == 0 ? HELPER_E_UNKNOWN : -value;
}

int open_flags(helper_u32 flags) {
    int result = 0;
    const bool readable = (flags & HELPER_OPEN_READ) != 0;
    const bool writable = (flags & HELPER_OPEN_WRITE) != 0;
    if (readable && writable)
        result |= O_RDWR;
    else if (writable)
        result |= O_WRONLY;
    else
        result |= O_RDONLY;
    if (flags & HELPER_OPEN_DIRECTORY) result |= O_DIRECTORY;
    if (flags & HELPER_OPEN_CREATE) result |= O_CREAT;
    if (flags & HELPER_OPEN_EXCL) result |= O_EXCL;
    if (flags & HELPER_OPEN_NOFOLLOW) result |= O_NOFOLLOW;
    if (flags & HELPER_OPEN_TRUNC) result |= O_TRUNC;
    if (flags & HELPER_OPEN_NONBLOCK) result |= O_NONBLOCK;
    if (flags & HELPER_OPEN_APPEND) result |= O_APPEND;
    result |= O_CLOEXEC;
    return result;
}

void fill_stat(struct helper_stat_info* out, const struct stat& info) {
    out->size = static_cast<helper_u64>(info.st_size);
    out->mode = static_cast<helper_u32>(info.st_mode);
    out->uid = static_cast<helper_u32>(info.st_uid);
    out->gid = static_cast<helper_u32>(info.st_gid);
    out->nlink = static_cast<helper_u32>(info.st_nlink);
    out->is_dir = S_ISDIR(info.st_mode) ? 1u : 0u;
    out->is_reg = S_ISREG(info.st_mode) ? 1u : 0u;
}

int platform_getuid(void) { return static_cast<int>(getuid()); }
int platform_geteuid(void) { return static_cast<int>(geteuid()); }
int platform_getpid(void) { return static_cast<int>(getpid()); }
void platform_abort(void) { abort(); }

int platform_read_self_path(char* out, helper_u32 capacity, helper_u32* length) {
    if (capacity == 0) return HELPER_E_INVAL;
    const ssize_t count = readlink("/proc/self/exe", out, capacity - 1);
    if (count < 0) return error_from_errno();
    out[count] = '\0';
    *length = static_cast<helper_u32>(count);
    return HELPER_OK;
}

int platform_read_boot_id(char* out, helper_u32 capacity, helper_u32* length) {
    if (capacity < HELPER_BOOT_ID_LEN + 1) return HELPER_E_OVERFLOW;
    const int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return error_from_errno();
    char buffer[HELPER_BOOT_ID_LEN + 2];
    ssize_t count;
    do {
        count = read(fd, buffer, sizeof(buffer));
    } while (count < 0 && errno == EINTR);
    close(fd);
    if (count != HELPER_BOOT_ID_LEN + 1 || buffer[HELPER_BOOT_ID_LEN] != '\n')
        return HELPER_E_INVAL;
    memcpy(out, buffer, HELPER_BOOT_ID_LEN);
    out[HELPER_BOOT_ID_LEN] = '\0';
    *length = HELPER_BOOT_ID_LEN;
    return HELPER_OK;
}

int platform_open_path(const char* path, helper_u32 flags, helper_u32 mode) {
    int fd;
    do { fd = open(path, open_flags(flags), static_cast<mode_t>(mode)); }
    while (fd < 0 && errno == EINTR);
    return fd < 0 ? error_from_errno() : fd;
}

int platform_openat(int dirfd, const char* name, helper_u32 flags, helper_u32 mode) {
    int fd;
    do { fd = openat(dirfd, name, open_flags(flags), static_cast<mode_t>(mode)); }
    while (fd < 0 && errno == EINTR);
    return fd < 0 ? error_from_errno() : fd;
}

int platform_fstat(int fd, struct helper_stat_info* out) {
    struct stat info;
    if (fstat(fd, &info) != 0) return error_from_errno();
    fill_stat(out, info);
    return HELPER_OK;
}

int platform_fstatat(int dirfd, const char* name, struct helper_stat_info* out,
                     helper_u32 flags) {
    struct stat info;
    const int system_flags = (flags & HELPER_AT_SYMLINK_NOFOLLOW) ? AT_SYMLINK_NOFOLLOW : 0;
    if (fstatat(dirfd, name, &info, system_flags) != 0) return error_from_errno();
    fill_stat(out, info);
    return HELPER_OK;
}

long long platform_read(int fd, void* buffer, helper_u32 capacity) {
    ssize_t count;
    do {
        count = read(fd, buffer, capacity);
    } while (count < 0 && errno == EINTR);
    return count < 0 ? error_from_errno() : static_cast<long long>(count);
}

long long platform_write(int fd, const void* buffer, helper_u32 length) {
    ssize_t count;
    do {
        count = write(fd, buffer, length);
    } while (count < 0 && errno == EINTR);
    return count < 0 ? error_from_errno() : static_cast<long long>(count);
}

long long platform_lseek(int fd, long long offset, int whence) {
    const off_t result = lseek(fd, static_cast<off_t>(offset), whence);
    return result < 0 ? error_from_errno() : static_cast<long long>(result);
}

int platform_close(int fd) { return close(fd) == 0 ? HELPER_OK : error_from_errno(); }
int platform_fsync(int fd) {
    int result;
    do { result = fsync(fd); } while (result < 0 && errno == EINTR);
    return result == 0 ? HELPER_OK : error_from_errno();
}

int platform_fchmod(int fd, helper_u32 mode) {
    return fchmod(fd, static_cast<mode_t>(mode)) == 0 ? HELPER_OK : error_from_errno();
}

int platform_fchown(int fd, helper_u32 uid, helper_u32 gid) {
    return fchown(fd, static_cast<uid_t>(uid), static_cast<gid_t>(gid)) == 0 ? HELPER_OK
                                                                            : error_from_errno();
}

int platform_set_xattr(int fd, const char* name, const char* value, helper_u32 length) {
    return fsetxattr(fd, name, value, length, 0) == 0 ? HELPER_OK : error_from_errno();
}

int platform_get_xattr(int fd, const char* name, char* out, helper_u32 capacity) {
    const ssize_t count = fgetxattr(fd, name, out, capacity);
    return count < 0 ? error_from_errno() : static_cast<int>(count);
}

int platform_mkdirat(int dirfd, const char* name, helper_u32 mode) {
    return mkdirat(dirfd, name, static_cast<mode_t>(mode)) == 0 ? HELPER_OK : error_from_errno();
}

int platform_renameat(int olddir, const char* oldname, int newdir, const char* newname) {
    return renameat(olddir, oldname, newdir, newname) == 0 ? HELPER_OK : error_from_errno();
}

int platform_unlinkat(int dirfd, const char* name, helper_u32 flags) {
    const int system_flags = (flags & HELPER_AT_REMOVEDIR) ? AT_REMOVEDIR : 0;
    return unlinkat(dirfd, name, system_flags) == 0 ? HELPER_OK : error_from_errno();
}

int platform_list_dir(int dirfd, char names[][HELPER_NAME_MAX], helper_u32 capacity,
                      helper_u32* count) {
    *count = 0;
    const int duplicate = dup(dirfd);
    if (duplicate < 0) return error_from_errno();
    DIR* directory = fdopendir(duplicate);
    if (directory == nullptr) {
        close(duplicate);
        return error_from_errno();
    }
    errno = 0;
    struct dirent* entry;
    int result = HELPER_OK;
    while ((entry = readdir(directory)) != nullptr) {
        const char* name = entry->d_name;
        if (name[0] == '.' && (name[1] == '\0' || (name[1] == '.' && name[2] == '\0')))
            continue;
        if (*count >= capacity) {
            result = HELPER_E_OVERFLOW;
            break;
        }
        const size_t length = strlen(name);
        if (length >= HELPER_NAME_MAX) {
            result = HELPER_E_NAMETOOLONG;
            break;
        }
        memcpy(names[*count], name, length + 1);
        ++*count;
        errno = 0;
    }
    if (result == HELPER_OK && errno != 0) result = error_from_errno();
    closedir(directory);
    return result;
}

int platform_flock(int fd, helper_u32 operation) {
    int system_operation = 0;
    if (operation & HELPER_LOCK_EX) system_operation |= LOCK_EX;
    if (operation & HELPER_LOCK_NB) system_operation |= LOCK_NB;
    if (operation & HELPER_LOCK_UN) system_operation |= LOCK_UN;
    return flock(fd, system_operation) == 0 ? HELPER_OK : error_from_errno();
}

int platform_execve(const char* path, char* const argv[], char* const envp[]) {
    execve(path, argv, envp);
    return error_from_errno();
}

int platform_run_shell(const char* command, char* out, helper_u32 capacity, helper_u32* length,
                       int* status) {
    *length = 0;
    *status = -1;
    int pipe_fds[2];
    if (pipe2(pipe_fds, O_CLOEXEC) != 0) return error_from_errno();
    const pid_t child = fork();
    if (child < 0) {
        close(pipe_fds[0]);
        close(pipe_fds[1]);
        return error_from_errno();
    }
    if (child == 0) {
        setpgid(0, 0);
        close(pipe_fds[0]);
        if (dup2(pipe_fds[1], STDOUT_FILENO) < 0) _exit(127);
        close(pipe_fds[1]);
        execl("/system/bin/sh", "sh", "-c", command, static_cast<char*>(nullptr));
        _exit(127);
    }
    close(pipe_fds[1]);
    helper_u32 stored = 0;
    char discard[4096];
    for (;;) {
        char* destination = stored < capacity ? out + stored : discard;
        const size_t remaining = stored < capacity ? capacity - stored : sizeof(discard);
        const ssize_t count = read(pipe_fds[0], destination, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        if (stored < capacity) stored += static_cast<helper_u32>(count);
    }
    close(pipe_fds[0]);
    int wait_status = 0;
    pid_t waited;
    do {
        waited = waitpid(child, &wait_status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited != child) return error_from_errno();
    *length = stored;
    *status = WIFEXITED(wait_status) ? WEXITSTATUS(wait_status) : -1;
    return HELPER_OK;
}

void* platform_dlopen_fd(int fd, helper_u32 flags) {
    char path[48];
    const int length = snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    if (length <= 0 || length >= static_cast<int>(sizeof(path))) return nullptr;
    const int system_flags = (flags & 2u) != 0 ? RTLD_NOW : RTLD_LAZY;
    return dlopen(path, system_flags | RTLD_LOCAL);
}

void* platform_dlsym(void* handle, const char* name) {
    dlerror();
    return dlsym(handle, name);
}

void platform_log_line(const char* text) {
    fprintf(stderr, "[helper] %s\n", text);
    fflush(stderr);
}

const struct helper_ops kPlatformOps = {
    platform_getuid,
    platform_geteuid,
    platform_getpid,
    platform_abort,
    platform_read_self_path,
    platform_read_boot_id,
    platform_open_path,
    platform_openat,
    platform_fstat,
    platform_fstatat,
    platform_read,
    platform_write,
    platform_lseek,
    platform_close,
    platform_fsync,
    platform_fchmod,
    platform_fchown,
    platform_set_xattr,
    platform_get_xattr,
    platform_mkdirat,
    platform_renameat,
    platform_unlinkat,
    platform_list_dir,
    platform_flock,
    platform_execve,
    platform_run_shell,
    platform_dlopen_fd,
    platform_dlsym,
    platform_log_line,
};

}  // namespace

int main(int argc, char* argv[], char* envp[]) {
    // The boot helper must never inherit a listener or a terminal expectation.
    setsid();
    return helper_run_boot(&kPlatformOps, argc, argv, envp);
}
