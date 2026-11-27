// Actual SU receipt consumer with syscall seams; no SDK root grant is available.
#include "../maplesu-sdk/sdk/src/payload/su/su_session_receipt.h"
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace { int mode, consumes, closes, raw; uid_t uid = 10498; bool scanned; }
extern "C" uid_t __wrap_getuid() { return uid; }
extern "C" uid_t __wrap_geteuid() { return mode == 14 ? 0 : uid; }
extern "C" int __wrap_getresuid(uid_t* a, uid_t* b, uid_t* c) {
    *a = *b = *c = uid; if (mode == 12) ++*c; return 0;
}
extern "C" int __wrap_getresgid(gid_t* a, gid_t* b, gid_t* c) { *a = *b = *c = uid; return 0; }
extern "C" DIR* __wrap_opendir(const char*) {
    if (mode == 2) { errno = EACCES; return nullptr; }
    return reinterpret_cast<DIR*>(1);
}
extern "C" int __wrap_dirfd(DIR*) { return 42; }
extern "C" int __wrap_closedir(DIR*) { return 0; }
extern "C" dirent* __wrap_readdir(DIR*) {
    static dirent item{};
    if (mode == 13) { errno = EIO; return nullptr; }
    if (scanned || mode < 3 || mode == 14) { errno = 0; return nullptr; }
    scanned = true; std::strcpy(item.d_name, "99"); return &item;
}
extern "C" ssize_t __wrap_readlinkat(int, const char*, char* out, size_t max) {
    if (mode == 9) { errno = ENOENT; return -1; }
    const char name[] = "anon_inode:[" MSU_SU_SESSION_NAME "]";
    if (sizeof(name) - 1 > max) std::abort();
    std::memcpy(out, name, sizeof(name) - 1); return sizeof(name) - 1;
}
extern "C" int __real_close(int);
extern "C" int __wrap_close(int fd) {
    if (fd != 99) return __real_close(fd);
    ++closes; if (mode == 11) { errno = EIO; return -1; } return 0;
}
extern "C" int __wrap_fcntl(int, int, ...) {
    if (mode == 10) { errno = EBADF; return -1; } return 0;
}
extern "C" int __wrap_ioctl(int, unsigned long command, ...) {
    ++consumes;
    if (mode == 4 || mode == 5) { errno = mode == 4 ? EINTR : EACCES; return -1; }
    va_list args; va_start(args, command); auto* out = va_arg(args, msu_su_session_receipt*); va_end(args);
    *out = {}; out->magic = MSU_SU_SESSION_MAGIC; out->version = MSU_SU_SESSION_VERSION;
    out->bytes = sizeof(*out); out->state = MSU_SU_SESSION_APPLIED | MSU_SU_SESSION_EXECUTED | MSU_SU_SESSION_CONSUMED;
    out->target_uid = uid; out->target_gid = uid;
    if (mode == 6) ++out->magic;
    if (mode == 7) out->state &= ~MSU_SU_SESSION_CONSUMED;
    if (mode == 8) ++out->target_uid;
    return 0;
}
extern "C" long __wrap_syscall(long, ...) { ++raw; errno = ENOENT; return -1; }
namespace maplesu::payload::su { bool acquire_root(); }
int main(int argc, char** argv) {
    mode = argc > 1 ? std::atoi(argv[1]) : 0;
    if (mode == 1 || mode == 2) uid = 0;
    errno = 0;
    const bool ok = maplesu::payload::su::acquire_root();
    bool pass = false;
    switch (mode) {
    case 0: case 14: pass = !ok && errno == EPERM && !consumes && !closes; break;
    case 1: pass = ok && !consumes && !closes; break;
    case 2: pass = !ok && errno == EACCES && !consumes && !closes; break;
    case 3: pass = ok && uid == 10498 && consumes == 1 && closes == 1; break;
    case 4: pass = !ok && errno == EINTR && consumes == 1 && closes == 1; break;
    case 5: pass = !ok && errno == EACCES && consumes == 1 && closes == 1; break;
    case 6: case 7: pass = !ok && errno == EPROTO && consumes == 1 && closes == 1; break;
    case 8: case 12: pass = !ok && errno == EPERM && consumes == 1 && closes == 1; break;
    case 9: pass = !ok && errno == ENOENT && !consumes && !closes; break;
    case 10: pass = !ok && errno == EBADF && !consumes && closes == 1; break;
    case 11: pass = !ok && errno == EIO && consumes == 1 && closes == 1; break;
    case 13: pass = !ok && errno == EIO && !consumes && !closes; break;
    }
    pass = pass && !raw;
    std::printf("case=%d pass=%d ok=%d errno=%d raw=%d consumes=%d closes=%d\n",
                mode, pass, ok, errno, raw, consumes, closes);
    return pass ? 0 : 1;
}
