#include "su_uid_copies.h"
#include "maplesu_storage_private.h"
#include "module_base_kernel_mem.h"
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

namespace {
constexpr char label[] = "u:object_r:system_file:s0";
thread_local bool frozen = false;
struct Fd { int value; ~Fd() { if (value >= 0) close(value); } };
bool uid_name(const char* name, uint32_t& uid) {
    if (strlen(name) != 10 || name[0] != 'u' || name[1] != '-') return false;
    uid = 0;
    for (unsigned i = 2; i < 10; ++i) {
        const char c = name[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        uid = (uid << 4) | uint32_t(c <= '9' ? c - '0' : c - 'a' + 10);
    }
    return uid != 0;
}
bool owned_directory(int fd, uint32_t uid) {
    struct stat st{};
    return fstat(fd, &st) == 0 && S_ISDIR(st.st_mode) && st.st_uid == 0 &&
        st.st_gid == uid && ((st.st_mode & 07777) == 0710 || (st.st_mode & 07777) == 0700);
}
bool close_directory(int fd) {
    struct stat st{};
    return fstat(fd, &st) == 0 && S_ISDIR(st.st_mode) && st.st_uid == 0 &&
        fchmod(fd, 0700) == 0 && fsync(fd) == 0;
}
bool repair_directory(int fd, uint32_t uid) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0) return false;
    if (st.st_gid == 0 && (st.st_mode & 07777) == 0700) {
        // A crash between mkdir and chown leaves this closed, root-only inode.
        // The root-owned parent prevents an application from substituting it.
        if (fsetxattr(fd, "security.selinux", label, sizeof(label), 0) != 0 || fchown(fd, 0, uid) != 0)
            return false;
    }
    return owned_directory(fd, uid);
}
bool remove_temporaries(int directory, uint32_t uid) {
    DIR* entries = fdopendir(dup(directory));
    if (!entries) return false;
    bool success = true;
    errno = 0;
    while (dirent* entry = readdir(entries)) {
        const char* name = entry->d_name;
        if (!strncmp(name, ".su-", 4)) {
            unsigned pid = 0, attempt = 0;
            char extra = 0;
            if (sscanf(name, ".su-%u-%u%c", &pid, &attempt, &extra) != 2 || !pid || attempt >= 32) {
                success = false; break;
            }
            struct stat st{};
            if (fstatat(directory, name, &st, AT_SYMLINK_NOFOLLOW) != 0 || !S_ISREG(st.st_mode) ||
                st.st_nlink != 1 || !((st.st_uid == 0 && st.st_gid == 0 && (st.st_mode & 07777) == 0600) ||
                    (st.st_uid == uid && st.st_gid == uid &&
                     ((st.st_mode & 07777) == 0600 || (st.st_mode & 07777) == 0700))) ||
                unlinkat(directory, name, 0) != 0) { success = false; break; }
        }
        errno = 0;
    }
    if (errno) success = false;
    closedir(entries);
    return success;
}
bool remove_copy(int root, const char* name, uint32_t uid) {
    Fd directory{openat(root, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (directory.value < 0 || !close_directory(directory.value) || !repair_directory(directory.value, uid) ||
        !remove_temporaries(directory.value, uid)) return false;
    // A foreign entry is never adopted or followed. This directory is root-owned
    // and not writable by its group, so the application cannot race publication.
    struct stat st{};
    if (fstatat(directory.value, "su", &st, AT_SYMLINK_NOFOLLOW) == 0) {
        if (!S_ISREG(st.st_mode) || st.st_uid != uid || st.st_nlink != 1 ||
            unlinkat(directory.value, "su", 0) != 0) return false;
    } else if (errno != ENOENT) return false;
    return unlinkat(root, name, AT_REMOVEDIR) == 0;
}
bool write_copy(int root, int source, uint32_t uid) {
    char name[16];
    snprintf(name, sizeof(name), "u-%08x", uid);
    bool created = mkdirat(root, name, 0700) == 0;
    if (!created && errno != EEXIST) return false;
    Fd directory{openat(root, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (directory.value < 0) return false;
    struct stat st{};
    if (created) {
        if (fstat(directory.value, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0 ||
            st.st_gid != 0 || (st.st_mode & 07777) != 0700 ||
            fsetxattr(directory.value, "security.selinux", label, sizeof(label), 0) != 0 ||
            fchown(directory.value, 0, uid) != 0) return false;
    }
    if (!repair_directory(directory.value, uid) || !remove_temporaries(directory.value, uid)) return false;
    if (fstatat(directory.value, "su", &st, AT_SYMLINK_NOFOLLOW) == 0) {
        if (!S_ISREG(st.st_mode) || st.st_uid != uid || st.st_gid != uid || st.st_nlink != 1)
            return false;
    } else if (errno != ENOENT) return false;
    std::string temporary;
    Fd output{-1};
    for (unsigned attempt = 0; attempt < 32; ++attempt) {
        temporary = ".su-" + std::to_string(getpid()) + "-" + std::to_string(attempt);
        output.value = openat(directory.value, temporary.c_str(), O_WRONLY | O_CREAT |
            O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (output.value >= 0) break;
        if (errno != EEXIST) return false;
    }
    if (output.value < 0) return false;
    bool success = lseek(source, 0, SEEK_SET) == 0;
    char bytes[16384];
    while (success) {
        const ssize_t size = read(source, bytes, sizeof(bytes));
        if (size < 0 && errno == EINTR) continue;
        if (size < 0) { success = false; break; }
        if (size == 0) break;
        ssize_t done = 0;
        while (done < size) {
            const ssize_t count = write(output.value, bytes + done, size - done);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { success = false; break; }
            done += count;
        }
    }
    if (success) success = fsetxattr(output.value, "security.selinux", label, sizeof(label), 0) == 0 &&
        fchown(output.value, uid, uid) == 0 && fchmod(output.value, 0700) == 0 && fsync(output.value) == 0;
    if (success) success = renameat(directory.value, temporary.c_str(), directory.value, "su") == 0;
    if (!success) unlinkat(directory.value, temporary.c_str(), 0);
    return success && fchmod(directory.value, frozen ? 0700 : 0710) == 0 && fsync(directory.value) == 0;
}
KModErr read_uids(uint64_t control, std::vector<uint32_t>& uids) {
    uint64_t table = 0;
    auto error = kernel_module::read_kernel_mem(control, &table, sizeof(table));
    if (is_failed(error)) return error;
    if (!table) return KModErr::OK;
    for (unsigned index = 0; index < 4096; ++index) {
        uint32_t uid = 0;
        error = kernel_module::read_kernel_mem(table + index * sizeof(uid), &uid, sizeof(uid));
        if (is_failed(error)) return error;
        if (!uid) return KModErr::OK;
        if (!uids.empty() && uid <= uids.back()) return KModErr::ERR_MODULE_PARAM;
        uids.push_back(uid);
    }
    return KModErr::ERR_MODULE_PARAM;
}
}

extern "C" KModErr maplesu_freeze_su_copies() {
    maplesu::storage::StorageContext context;
    const auto located = maplesu::storage::current_context(context);
    if (is_failed(located)) return located;
    const std::string name = context.locator.system_component;
    Fd data{open("/data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    Fd root{data.value < 0 ? -1 : openat(data.value, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    struct stat st{};
    if (root.value < 0 || fstat(root.value, &st) != 0 || st.st_uid != 0 || st.st_gid != 0 ||
        (st.st_mode & 0022)) return KModErr::ERR_MODULE_OPEN_DIR;
    DIR* entries = fdopendir(dup(root.value));
    if (!entries) return KModErr::ERR_MODULE_OPEN_DIR;
    bool success = true;
    errno = 0;
    while (dirent* entry = readdir(entries)) {
        uint32_t uid = 0;
        if (uid_name(entry->d_name, uid)) {
            Fd directory{openat(root.value, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
            if (directory.value < 0 || !close_directory(directory.value) ||
                !repair_directory(directory.value, uid)) success = false;
        }
        errno = 0;
    }
    if (errno) success = false;
    closedir(entries);
    if (!success) return KModErr::ERR_MODULE_WRITE_FILE;
    frozen = true;
    return KModErr::OK;
}

extern "C" void maplesu_thaw_su_copies() { frozen = false; }

extern "C" KModErr maplesu_sync_su_copies(const std::vector<uint32_t>& uids) {
    Fd data{open("/data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    maplesu::storage::StorageContext context;
    const auto located = maplesu::storage::current_context(context);
    if (is_failed(located)) return located;
    const std::string name = context.locator.system_component;
    Fd root{data.value < 0 ? -1 : openat(data.value, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    struct stat st{};
    if (root.value < 0 || fstat(root.value, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0 ||
        st.st_gid != 0 || (st.st_mode & 0022)) return KModErr::ERR_MODULE_OPEN_DIR;
    Fd private_directory{openat(root.value, "u-00000000", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (private_directory.value < 0 || fstat(private_directory.value, &st) != 0 ||
        st.st_uid != 0 || st.st_gid != 0 || (st.st_mode & 07777) != 0700)
        return KModErr::ERR_MODULE_OPEN_DIR;
    Fd source{openat(private_directory.value, "su", O_RDONLY | O_NOFOLLOW | O_CLOEXEC)};
    if (source.value < 0 || fstat(source.value, &st) != 0 || !S_ISREG(st.st_mode) ||
        st.st_uid != 0 || st.st_gid != 0 || st.st_nlink != 1 || (st.st_mode & 07777) != 0700)
        return KModErr::ERR_MODULE_OPEN_FILE;
    DIR* entries = fdopendir(dup(root.value));
    if (!entries) return KModErr::ERR_MODULE_OPEN_DIR;
    bool success = true;
    errno = 0;
    while (dirent* entry = readdir(entries)) {
        uint32_t uid = 0;
        if (uid_name(entry->d_name, uid) && std::find(uids.begin(), uids.end(), uid) == uids.end()) {
            if (!remove_copy(root.value, entry->d_name, uid)) { success = false; break; }
        }
        errno = 0;
    }
    if (errno) success = false;
    closedir(entries);
    if (!success) return KModErr::ERR_MODULE_WRITE_FILE;
    for (uint32_t uid : uids)
        if (uid && !write_copy(root.value, source.value, uid)) return KModErr::ERR_MODULE_WRITE_FILE;
    return fsync(root.value) == 0 ? KModErr::OK : KModErr::ERR_MODULE_WRITE_FILE;
}

extern "C" KModErr maplesu_sync_su_copies_from_control(uint64_t control) {
    std::vector<uint32_t> uids;
    const auto error = read_uids(control, uids);
    if (is_failed(error)) return error;
    return maplesu_sync_su_copies(uids);
}

extern "C" KModErr maplesu_open_su_copies_from_control(uint64_t control) {
    if (!frozen) return KModErr::ERR_MODULE_PARAM;
    std::vector<uint32_t> uids;
    const auto error = read_uids(control, uids);
    if (is_failed(error)) return error;
    maplesu::storage::StorageContext context;
    const auto located = maplesu::storage::current_context(context);
    if (is_failed(located)) return located;
    const std::string name = context.locator.system_component;
    Fd data{open("/data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    Fd root{data.value < 0 ? -1 : openat(data.value, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    struct stat source{};
    if (root.value < 0 || fstat(root.value, &source) != 0 || source.st_uid != 0 || source.st_gid != 0 ||
        (source.st_mode & 0022)) return KModErr::ERR_MODULE_OPEN_DIR;
    Fd private_directory{openat(root.value, "u-00000000", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (private_directory.value < 0 || fstat(private_directory.value, &source) != 0 ||
        source.st_uid != 0 || source.st_gid != 0 || (source.st_mode & 07777) != 0700)
        return KModErr::ERR_MODULE_OPEN_DIR;
    if (fstatat(private_directory.value, "su", &source, AT_SYMLINK_NOFOLLOW) != 0 || !S_ISREG(source.st_mode) ||
        source.st_uid != 0 || source.st_gid != 0 || source.st_nlink != 1 ||
        (source.st_mode & 07777) != 0700) return KModErr::ERR_MODULE_OPEN_FILE;
    // Verify the entire prepared set before opening any delivery directory.
    for (uint32_t uid : uids) {
        char path[16];
        snprintf(path, sizeof(path), "u-%08x", uid);
        Fd directory{openat(root.value, path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
        struct stat st{};
        if (directory.value < 0 || !owned_directory(directory.value, uid) ||
            fstat(directory.value, &st) != 0 || (st.st_mode & 07777) != 0700 ||
            fstatat(directory.value, "su", &st, AT_SYMLINK_NOFOLLOW) != 0 || !S_ISREG(st.st_mode) ||
            st.st_uid != uid || st.st_gid != uid || st.st_nlink != 1 ||
            (st.st_mode & 07777) != 0700 || st.st_size != source.st_size)
            return KModErr::ERR_MODULE_OPEN_FILE;
    }
    for (uint32_t uid : uids) {
        char path[16];
        snprintf(path, sizeof(path), "u-%08x", uid);
        Fd directory{openat(root.value, path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
        if (directory.value < 0 || !owned_directory(directory.value, uid) ||
            fchmod(directory.value, 0710) != 0 || fsync(directory.value) != 0)
            return KModErr::ERR_MODULE_WRITE_FILE;
    }
    return KModErr::OK;
}
