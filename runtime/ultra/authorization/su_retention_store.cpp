#include "su_retention_store.h"
#include "maplesu_storage_private.h"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>


namespace {
constexpr const char* record = "uid_retention_budget.20261105";
struct Descriptor {
    int value;
    explicit Descriptor(int fd) : value(fd) {}
    ~Descriptor() { if (value >= 0) close(value); }
};

bool identifier(const char* text, size_t size) {
    if (!text || strnlen(text, size + 1) != size) return false;
    for (size_t index = 0; index < size; ++index) {
        const char c = text[index];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')))
            return false;
    }
    return true;
}

bool directory(int fd) {
    struct stat st{};
    return fd >= 0 && fstat(fd, &st) == 0 && S_ISDIR(st.st_mode) &&
        st.st_uid == 0 && st.st_gid == 0 && (st.st_mode & 0022) == 0;
}

int open_child(int parent, const char* name, bool private_mode) {
    const int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (!directory(fd) || (private_mode && fchmod(fd, 0700) != 0)) {
        if (fd >= 0) close(fd);
        return -1;
    }
    return fd;
}

bool synchronize(int fd) {
    int result;
    do { result = fsync(fd); } while (result < 0 && errno == EINTR);
    return result == 0;
}
}

KModErr maplesu_store_su_retention(const void* ledger, uint64_t size) {
    if (!ledger || size != 64 || static_cast<const unsigned char*>(ledger)[0] != 1)
        return KModErr::ERR_MODULE_STORAGE_TYPE;
    maplesu::storage::StorageContext storage_context;
    const auto located = maplesu::storage::current_context(storage_context);
    if (is_failed(located)) return located;
    const char* module = storage_context.module_id.c_str();
    if (!identifier(module, 32)) return KModErr::ERR_MODULE_PARAM;
    const char* owner = storage_context.locator.system_component;
    Descriptor data(open("/data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    // /data is Android-owned and may be group-writable; validate the private
    // component and every descendant before using descriptor-relative paths.
    if (data.value < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    Descriptor root(open_child(data.value, owner, false));
    Descriptor storage(root.value < 0 ? -1 : open_child(root.value, "storage", true));
    Descriptor target(storage.value < 0 ? -1 : open_child(storage.value, module, true));
    if (target.value < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    struct stat old{};
    if (fstatat(target.value, record, &old, AT_SYMLINK_NOFOLLOW) == 0) {
        if (!S_ISREG(old.st_mode) || old.st_uid != 0 || old.st_gid != 0 || old.st_nlink != 1)
            return KModErr::ERR_MODULE_OPEN_FILE;
    } else if (errno != ENOENT) return KModErr::ERR_MODULE_OPEN_FILE;
    char temporary[96]{};
    int fd = -1;
    for (unsigned attempt = 0; attempt < 32; ++attempt) {
        snprintf(temporary, sizeof(temporary), "%s.tmp.%ld.%u", record, static_cast<long>(getpid()), attempt);
        fd = openat(target.value, temporary, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    if (fd < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    bool valid = fstat(fd, &old) == 0 && S_ISREG(old.st_mode) && old.st_uid == 0 &&
        old.st_gid == 0 && old.st_nlink == 1 && fchmod(fd, 0600) == 0;
    static const char context[] = "u:object_r:system_file:s0";
    if (valid) valid = fsetxattr(fd, "security.selinux", context, sizeof(context), 0) == 0;
    size_t written = 0;
    while (valid && written < size) {
        const auto count = write(fd, static_cast<const char*>(ledger) + written, size - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { valid = false; break; }
        written += static_cast<size_t>(count);
    }
    unsigned char observed[64]{};
    size_t read = 0;
    while (valid && read < sizeof(observed)) {
        const auto count = pread(fd, observed + read, sizeof(observed) - read, read);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { valid = false; break; }
        read += static_cast<size_t>(count);
    }
    if (valid) valid = fstat(fd, &old) == 0 && old.st_size == 64 &&
        memcmp(observed, ledger, sizeof(observed)) == 0 && synchronize(fd);
    if (close(fd) != 0) valid = false;
    if (valid) valid = renameat(target.value, temporary, target.value, record) == 0;
    if (valid) valid = synchronize(target.value);
    if (!valid) unlinkat(target.value, temporary, 0);
    return valid ? KModErr::OK : KModErr::ERR_MODULE_STORAGE_WRITE;
}
