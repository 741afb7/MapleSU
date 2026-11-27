// The canonical keyed image is root-only. Authorized UIDs execute separate
// copies inside root-owned, UID-group traversal directories.
#include "module_err_def.h"
#include "maplesu_storage_private.h"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

namespace {
struct Fd {
    int value;
    ~Fd() { if (value >= 0) close(value); }
};
constexpr char system_context[] = "u:object_r:system_file:s0";

bool has_context(int fd) {
    char label[128]{};
    const auto size = fgetxattr(fd, "security.selinux", label, sizeof(label));
    return (size == sizeof(system_context) || size == sizeof(system_context) - 1) &&
        memcmp(label, system_context, sizeof(system_context) - 1) == 0 &&
        (size != sizeof(system_context) || label[size - 1] == '\0');
}
KModErr prepare_inode(int fd, bool directory) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_uid != 0 ||
        (directory ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode) || st.st_nlink != 1 || st.st_size == 0))
        return KModErr::ERR_MODULE_OPEN_FILE;
    const mode_t mode = directory ? 0711 : 0700;
    if ((st.st_mode & 07777) != mode && fchmod(fd, mode) != 0)
        return KModErr::ERR_SET_FILE_SELINUX;
    if (!has_context(fd) && fsetxattr(fd, "security.selinux", system_context, sizeof(system_context), 0) != 0)
        return KModErr::ERR_SET_FILE_SELINUX;
    if (fstat(fd, &st) != 0 || (st.st_mode & 07777) != mode || !has_context(fd))
        return KModErr::ERR_SET_FILE_SELINUX;
    return KModErr::OK;
}
}

extern "C" KModErr maplesu_prepare_su_exec() {
    std::string directory;
    const auto error = maplesu::storage::current_system_directory(directory);
    if (is_failed(error)) return error;
    return maplesu_prepare_su_exec_directory(directory.c_str());
}
