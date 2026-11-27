
//
#include "../compat/module_install_legacy_abi.h"
#include "operations.h"
#include "maplesu_module_install_private.h"
#include "state.h"
#include "maplesu_module_control.h"
#include "maplesu_storage_private.h"
#include "maplesu_storage_worker_private.h"
#include "lifecycle.h"
#include "path_contract.h"
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <functional>
#include <iostream>
#include <limits.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <sys/syscall.h>
#include <vector>
#include "zip.h"

// forked mutation worker through the module-control bridge instead of calling
// `default_modules::module_root::set_current_root` / `_exit` directly.
// .text+0x810 and .text+0xd54 -> maplesu_lock_module_root; +0x270, +0xb7c
// (exit code 127) and +0x13e0 (exit code 0) -> maplesu_module_worker_exit;
extern "C" KModErr maplesu_lock_module_root(bool allow);
extern "C" [[noreturn]] void maplesu_module_worker_exit(int code);

namespace maplesu::compat {
static module::SdkVersion current_sdk_version() {
    const auto version = maplesu::sdk::get_sdk_version();
    return {version.major, version.minor, version.patch};
}
}

namespace file_utils {
namespace {
class OwnedFd {
public:
    explicit OwnedFd(int fd = -1) : fd_(fd) {}
    ~OwnedFd() { if (fd_ >= 0) close(fd_); }
    OwnedFd(const OwnedFd&) = delete;
    OwnedFd& operator=(const OwnedFd&) = delete;
    int get() const { return fd_; }
    int release() { const int fd = fd_; fd_ = -1; return fd; }
    bool close_checked() { return fd_ < 0 || close(release()) == 0; }
    bool reset(int fd) {
        const bool closed = close_checked();
        fd_ = fd;
        return closed;
    }
private:
    int fd_;
};

static int open_directory_at(int parent, const char* name, bool create) {
    if (create && mkdirat(parent, name, 0777) != 0 && errno != EEXIST) return -1;
    OwnedFd directory(openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (directory.get() < 0) return -1;
    struct stat st{};
    if (fstat(directory.get(), &st) != 0 || !S_ISDIR(st.st_mode)) return -1;
    return directory.release();
}

static bool set_system_access_mode(int fd) {
    if (fchmod(fd, 0777) != 0) return false;
    static constexpr char context[] = "u:object_r:system_file:s0";
    return fsetxattr(fd, "security.selinux", context, sizeof(context), 0) == 0;
}

static bool delete_entry_at(int parent, const char* name) {
    struct stat before{};
    if (fstatat(parent, name, &before, AT_SYMLINK_NOFOLLOW) != 0) return errno == ENOENT;
    if (!S_ISDIR(before.st_mode)) return unlinkat(parent, name, 0) == 0;
    OwnedFd child(open_directory_at(parent, name, false));
    struct stat opened{};
    if (child.get() < 0 || fstat(child.get(), &opened) != 0 ||
        opened.st_dev != before.st_dev || opened.st_ino != before.st_ino) return false;
    DIR* directory = fdopendir(child.get());
    if (!directory) return false;
    child.release();
    bool deleted = true;
    for (;;) {
        errno = 0;
        dirent* entry = readdir(directory);
        if (!entry) { if (errno) deleted = false; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (!delete_entry_at(dirfd(directory), entry->d_name)) { deleted = false; break; }
    }
    if (closedir(directory) != 0) deleted = false;
    struct stat current{};
    if (!deleted || fstatat(parent, name, &current, AT_SYMLINK_NOFOLLOW) != 0 ||
        current.st_dev != before.st_dev || current.st_ino != before.st_ino) return false;
    return unlinkat(parent, name, AT_REMOVEDIR) == 0;
}
}

static bool read_text_file(const char* path, std::string& text) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    file.seekg(0, std::ios::end);
    std::streamoff size = file.tellg();
    if (size < 0) return false;
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> bytes;
    bytes.resize(static_cast<size_t>(size));
    if (!file.read(reinterpret_cast<char*>(bytes.data()), size)) return false;
    text.assign(bytes.begin(), bytes.end());
    return true;
}
}

namespace maplesu::module_install {
namespace {
static KModErr root_worker(storage::WorkerScope& worker) {
    RETURN_IF_ERROR(worker.root());
    return maplesu_lock_module_root(true);
}

constexpr int kCommittedStartupFailureExit = 73;
constexpr int kRolledBackFailureExit = 74;
constexpr int kCommittedCleanupPendingExit = 75;
constexpr int kCommittedStartupAndCleanupFailureExit = 76;
constexpr int kCommittedDiagnosticExit = 77;
constexpr int kCommittedAwaitingBootExit = 78;
constexpr int kUncommittedCleanupPendingExit = 79;

using maplesu::module_lifecycle::ModuleMutation;
using file_utils::OwnedFd;

static int open_system_directory(KModErr& context_error) {
    storage::StorageContext context;
    context_error = storage::current_context(context);
    if (is_failed(context_error)) {
        errno = context_error == KModErr::ERR_MODULE_ROOT_KEY ? EACCES : EINVAL;
        return -1;
    }
    OwnedFd data(open("/data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (data.get() < 0) return -1;
    const std::string system_name = context.locator.system_component;
    if (!module_paths::valid_id(system_name)) { errno = EINVAL; return -1; }
    OwnedFd system(file_utils::open_directory_at(data.get(), system_name.c_str(), false));
    const int open_error = errno;
    if (!data.close_checked()) return -1;
    if (system.get() < 0) { errno = open_error; return -1; }
    return system.release();
}

static KModErr validate_uninstall_callback(int system, const char* id, bool& image_present) {
    image_present = false;
    OwnedFd modules(file_utils::open_directory_at(system, "modules", false));
    if (modules.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_DEL_DIR;
    struct stat st{};
    if (fstatat(modules.get(), id, &st, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno != ENOENT) return KModErr::ERR_MODULE_DEL_DIR;
        return modules.close_checked() ? KModErr::OK : KModErr::ERR_MODULE_DEL_DIR;
    }
    if (!S_ISDIR(st.st_mode)) return KModErr::ERR_MODULE_DEL_DIR;
    OwnedFd module(file_utils::open_directory_at(modules.get(), id, false));
    if (module.get() < 0) return KModErr::ERR_MODULE_DEL_DIR;
    if (fstatat(module.get(), id, &st, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno != ENOENT) return KModErr::ERR_MODULE_READ_FILE;
    } else {
        if (!S_ISREG(st.st_mode)) return KModErr::ERR_MODULE_READ_FILE;
        image_present = true;
    }
    if (!module.close_checked() || !modules.close_checked()) return KModErr::ERR_MODULE_DEL_DIR;
    return KModErr::OK;
}

static bool sync_directory(int fd) {
    int result;
    do { result = fsync(fd); } while (result < 0 && errno == EINTR);
    return result == 0;
}

static bool delete_from_container(int system, const char* container, const char* id) {
    OwnedFd directory(file_utils::open_directory_at(system, container, false));
    if (directory.get() < 0) return errno == ENOENT;
    const bool deleted = file_utils::delete_entry_at(directory.get(), id);
    const bool durable = deleted && sync_directory(directory.get());
    return directory.close_checked() && durable;
}

static bool delete_override_from_container(int system, const char* container, const char* id) {
    OwnedFd directory(file_utils::open_directory_at(system, container, false));
    if (directory.get() < 0) return errno == ENOENT;
    struct stat st{};
    bool deleted;
    if (fstatat(directory.get(), id, &st, AT_SYMLINK_NOFOLLOW) != 0) deleted = errno == ENOENT;
    else deleted = !S_ISDIR(st.st_mode) && unlinkat(directory.get(), id, 0) == 0;
    const bool durable = deleted && sync_directory(directory.get());
    return directory.close_checked() && durable;
}

constexpr const char* kInstallDirectory = ".module-install";
constexpr const char* kInstallContainers[] = {"modules", "storage", "modules_new_state", "modules_new_desc"};
constexpr const char* kInstallNew[] = {"new-modules", "new-storage", "new-state", "new-desc"};
constexpr const char* kInstallOld[] = {"old-modules", "old-storage", "old-state", "old-desc"};
struct InstallRecord {
    uint32_t magic = 0x4d534936;
    uint32_t old_mask = 0;
    uint32_t state_mask = 0;
    uint32_t committed = 0;
    uint32_t cleaned = 0;
    uint64_t instance[2] = {};
};
struct InstallIdentity {
    uint32_t magic = 0x4d53494e;
    char id[1024] = {};
    uint64_t instance[2] = {};
};
static bool install_transfer(int fd, void* data, size_t bytes, bool writing) {
    auto* cursor = static_cast<char*>(data);
    while (bytes) {
        const ssize_t count = writing ? write(fd, cursor, bytes) : read(fd, cursor, bytes);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes -= static_cast<size_t>(count);
        cursor += count;
    }
    return true;
}

static int open_private_directory(int parent, const char* name, bool create) {
    if (create && mkdirat(parent, name, 0700) != 0 && errno != EEXIST) return -1;
    OwnedFd directory(file_utils::open_directory_at(parent, name, false));
    struct stat st{};
    if (directory.get() < 0 || fstat(directory.get(), &st) || st.st_uid || (st.st_mode & 0077)) {
        if (directory.get() >= 0) errno = EACCES;
        return -1;
    }
    return directory.release();
}

static bool copy_metadata(int source, int target) {
    struct stat st{};
    if (fstat(source, &st) || fchown(target, st.st_uid, st.st_gid) || fchmod(target, st.st_mode & 07777)) return false;
    const ssize_t size = flistxattr(source, nullptr, 0);
    if (size < 0) return errno == ENOTSUP;
    std::vector<char> names(static_cast<size_t>(size));
    if (size && flistxattr(source, names.data(), names.size()) != size) return false;
    for (size_t offset = 0; offset < names.size();) {
        const char* name = names.data() + offset;
        const size_t length = strnlen(name, names.size() - offset);
        if (length == names.size() - offset) return false;
        const ssize_t bytes = fgetxattr(source, name, nullptr, 0);
        if (bytes < 0) return false;
        std::vector<char> value(static_cast<size_t>(bytes));
        if (fgetxattr(source, name, value.data(), value.size()) != bytes ||
            fsetxattr(target, name, value.data(), value.size(), 0)) return false;
        offset += length + 1;
    }
    return true;
}

static bool copy_entry_at(int source, const char* name, int target, const char* output) {
    struct stat st{};
    if (fstatat(source, name, &st, AT_SYMLINK_NOFOLLOW)) return false;
    if (S_ISLNK(st.st_mode)) {
        std::vector<char> link(static_cast<size_t>(std::max<off_t>(st.st_size + 1, PATH_MAX)));
        const ssize_t size = readlinkat(source, name, link.data(), link.size());
        if (size < 0 || static_cast<size_t>(size) == link.size()) return false;
        link[static_cast<size_t>(size)] = 0;
        return symlinkat(link.data(), target, output) == 0 &&
            fchownat(target, output, st.st_uid, st.st_gid, AT_SYMLINK_NOFOLLOW) == 0;
    }
    if (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode)) {
        return mknodat(target, output, st.st_mode, st.st_rdev) == 0 &&
            fchownat(target, output, st.st_uid, st.st_gid, AT_SYMLINK_NOFOLLOW) == 0;
    }
    OwnedFd input(openat(source, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC |
                         (S_ISDIR(st.st_mode) ? O_DIRECTORY : 0)));
    if (input.get() < 0) return false;
    if (S_ISDIR(st.st_mode) && mkdirat(target, output, 0700)) return false;
    OwnedFd out(openat(target, output, O_NOFOLLOW | O_CLOEXEC |
                       (S_ISDIR(st.st_mode) ? O_RDONLY | O_DIRECTORY : O_WRONLY | O_CREAT | O_EXCL), 0600));
    if (out.get() < 0) return false;
    if (S_ISDIR(st.st_mode)) {
        OwnedFd scan(dup(input.get()));
        DIR* entries = fdopendir(scan.get());
        if (!entries) return false;
        scan.release();
        bool copied = true;
        for (;;) {
            errno = 0;
            dirent* entry = readdir(entries);
            if (!entry) { if (errno) copied = false; break; }
            if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
            if (!copy_entry_at(input.get(), entry->d_name, out.get(), entry->d_name)) { copied = false; break; }
        }
        if (closedir(entries)) copied = false;
        if (!copied) return false;
    } else {
        char bytes[8192];
        for (;;) {
            const ssize_t count = read(input.get(), bytes, sizeof(bytes));
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) return false;
            if (!count) break;
            for (ssize_t done = 0; done < count;) {
                const ssize_t written = write(out.get(), bytes + done, static_cast<size_t>(count - done));
                if (written < 0 && errno == EINTR) continue;
                if (written <= 0) return false;
                done += written;
            }
        }
    }
    return copy_metadata(input.get(), out.get()) && sync_directory(out.get()) &&
        out.close_checked() && input.close_checked();
}

static KModErr read_install_record(int transaction, InstallRecord& record, bool& present) {
    present = false;
    OwnedFd input(openat(transaction, "record", O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    if (input.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_READ_FILE;
    struct stat st{};
    if (fstat(input.get(), &st) || !S_ISREG(st.st_mode) || st.st_uid || st.st_nlink != 1 ||
        (st.st_mode & 0077) || st.st_size != sizeof(record)) return KModErr::ERR_MODULE_READ_FILE;
    ssize_t got;
    do { got = read(input.get(), &record, sizeof(record)); } while (got < 0 && errno == EINTR);
    if (got != sizeof(record) || record.magic != 0x4d534936 || record.old_mask > 15 ||
        record.state_mask > 31 || record.committed > 1 || record.cleaned > 1 ||
        !(record.instance[0] | record.instance[1]) || !input.close_checked())
        return KModErr::ERR_MODULE_READ_FILE;
    present = true;
    return KModErr::OK;
}

static KModErr write_install_record(int transaction, const InstallRecord& record) {
    if (unlinkat(transaction, ".record", 0) && errno != ENOENT) return KModErr::ERR_MODULE_WRITE_FILE;
    OwnedFd output(openat(transaction, ".record", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (output.get() < 0) return KModErr::ERR_MODULE_WRITE_FILE;
    for (size_t done = 0; done < sizeof(record);) {
        const ssize_t written = write(output.get(), reinterpret_cast<const char*>(&record) + done, sizeof(record) - done);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return KModErr::ERR_MODULE_WRITE_FILE;
        done += static_cast<size_t>(written);
    }
    if (!sync_directory(output.get()) || !output.close_checked() ||
        renameat(transaction, ".record", transaction, "record") || !sync_directory(transaction))
        return KModErr::ERR_MODULE_WRITE_FILE;
    return KModErr::OK;
}

static KModErr recover_installation_at(int system, int root, int transaction, const char* id,
                                     InstallRecord record, bool present) {
    if (present && record.committed && record.cleaned) return KModErr::OK;
    if (present && !record.committed) {
        for (unsigned index = 0; index < 4; ++index) {
            OwnedFd container(file_utils::open_directory_at(system, kInstallContainers[index], true));
            if (container.get() < 0) return KModErr::ERR_MODULE_CREATE_DIR;
            struct stat backup{};
            if (fstatat(transaction, kInstallOld[index], &backup, AT_SYMLINK_NOFOLLOW) == 0) {
                if (!file_utils::delete_entry_at(container.get(), id) ||
                    renameat(transaction, kInstallOld[index], container.get(), id)) return KModErr::ERR_MODULE_DEL_DIR;
            } else if (errno != ENOENT) return KModErr::ERR_MODULE_READ_FILE;
            else if (!(record.old_mask & (1u << index)) && !file_utils::delete_entry_at(container.get(), id))
                return KModErr::ERR_MODULE_DEL_DIR;
            else if ((record.old_mask & (1u << index)) &&
                     fstatat(container.get(), id, &backup, AT_SYMLINK_NOFOLLOW)) return KModErr::ERR_MODULE_READ_FILE;
            if (!sync_directory(container.get()) || !sync_directory(transaction)) return KModErr::ERR_MODULE_WRITE_FILE;
        }
        for (unsigned index = 0; index < 5; ++index) {
            const auto state = static_cast<module_state::List>(index);
            KModErr result = (record.state_mask & (1u << index)) ? module_state::add(state, id) : module_state::remove(state, id);
            RETURN_IF_ERROR(result);
        }
    }
    if (present) RETURN_IF_ERROR(module_state::remove(module_state::List::PendingRemoval, id));
    // Keep the record until restoration and the barrier retirement are durable.
    for (const auto* name : kInstallNew) if (!file_utils::delete_entry_at(transaction, name)) return KModErr::ERR_MODULE_DEL_DIR;
    for (const auto* name : kInstallOld) if (!file_utils::delete_entry_at(transaction, name)) return KModErr::ERR_MODULE_DEL_DIR;
    if (present && record.committed) {
        record.cleaned = 1;
        return write_install_record(transaction, record);
    }
    if (!sync_directory(transaction) || !file_utils::delete_entry_at(root, id) || !sync_directory(root))
        return KModErr::ERR_MODULE_DEL_DIR;
    return KModErr::OK;
}

static KModErr recover_installation_impl(const char* id, bool& found,
                                        const InstallIdentity* expected = nullptr, bool* committed = nullptr) {
    found = false;
    KModErr context_error;
    OwnedFd system(open_system_directory(context_error));
    RETURN_IF_ERROR(context_error);
    if (system.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    OwnedFd root(open_private_directory(system.get(), kInstallDirectory, false));
    if (root.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    OwnedFd transaction(open_private_directory(root.get(), id, false));
    if (transaction.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    found = true;
    InstallRecord record;
    bool present = false;
    RETURN_IF_ERROR(read_install_record(transaction.get(), record, present));
    if (expected && (!present || memcmp(record.instance, expected->instance, sizeof(record.instance))))
        return KModErr::ERR_MODULE_READ_FILE;
    if (present && record.committed && !sync_directory(transaction.get())) return KModErr::ERR_MODULE_WRITE_FILE;
    if (committed) *committed = present && record.committed;
    if (!expected && present && record.committed && record.cleaned) {
        return file_utils::delete_entry_at(root.get(), id) && sync_directory(root.get())
            ? KModErr::OK : KModErr::ERR_MODULE_DEL_DIR;
    }
    return recover_installation_at(system.get(), root.get(), transaction.get(), id, record, present);
}

static KModErr prepare_installation(int system, const char* id, OwnedFd& root,
                                   OwnedFd& transaction, InstallRecord& record) {
    if (!root.reset(open_private_directory(system, kInstallDirectory, true)) || root.get() < 0 ||
        !transaction.reset(open_private_directory(root.get(), id, true)) || transaction.get() < 0)
        return KModErr::ERR_MODULE_CREATE_DIR;
    for (unsigned index = 0; index < 5; ++index) {
        std::vector<std::string> ids;
        RETURN_IF_ERROR(module_state::read(static_cast<module_state::List>(index), ids));
        if (std::find(ids.begin(), ids.end(), id) != ids.end()) record.state_mask |= 1u << index;
    }
    for (unsigned index = 0; index < 4; ++index) {
        OwnedFd container(file_utils::open_directory_at(system, kInstallContainers[index], false));
        if (container.get() < 0) { if (errno == ENOENT) continue; return KModErr::ERR_MODULE_OPEN_DIR; }
        struct stat entry{};
        if (fstatat(container.get(), id, &entry, AT_SYMLINK_NOFOLLOW)) {
            if (errno == ENOENT) continue;
            return KModErr::ERR_MODULE_READ_FILE;
        }
        record.old_mask |= 1u << index;
    }
    RETURN_IF_ERROR(write_install_record(transaction.get(), record));
    if (!sync_directory(root.get()) || !sync_directory(system)) return KModErr::ERR_MODULE_WRITE_FILE;
    RETURN_IF_ERROR(module_state::add(module_state::List::PendingRemoval, id));
    for (unsigned index = 0; index < 4; ++index) if (record.old_mask & (1u << index)) {
        OwnedFd container(file_utils::open_directory_at(system, kInstallContainers[index], false));
        if (container.get() < 0 || !copy_entry_at(container.get(), id, transaction.get(), kInstallNew[index]))
            return KModErr::ERR_MODULE_WRITE_FILE;
    }
    if (!(record.old_mask & 1)) {
        OwnedFd image(file_utils::open_directory_at(transaction.get(), kInstallNew[0], true));
        if (image.get() < 0 || !file_utils::set_system_access_mode(image.get())) return KModErr::ERR_MODULE_CREATE_DIR;
    }
    return sync_directory(transaction.get()) ? KModErr::OK : KModErr::ERR_MODULE_WRITE_FILE;
}

static KModErr publish_installation(int system, int transaction, const char* id, const InstallRecord& record) {
    for (unsigned index = 0; index < 4; ++index) {
        OwnedFd container(file_utils::open_directory_at(system, kInstallContainers[index], true));
        if (container.get() < 0) return KModErr::ERR_MODULE_CREATE_DIR;
        if ((record.old_mask & (1u << index)) && renameat(container.get(), id, transaction, kInstallOld[index]))
            return KModErr::ERR_MODULE_WRITE_FILE;
        if (!sync_directory(container.get()) || !sync_directory(transaction)) return KModErr::ERR_MODULE_WRITE_FILE;
        if ((index == 0 || (record.old_mask & (1u << index))) &&
            renameat(transaction, kInstallNew[index], container.get(), id)) return KModErr::ERR_MODULE_WRITE_FILE;
        if (!sync_directory(container.get()) || !sync_directory(transaction)) return KModErr::ERR_MODULE_WRITE_FILE;
    }
    return KModErr::OK;
}

static InstallCompletion recover_interrupted_install(const InstallIdentity& identity) {
    InstallCompletion completion;
    if (!memchr(identity.id, 0, sizeof(identity.id)) || !module_paths::valid_id(identity.id) ||
        !(identity.instance[0] | identity.instance[1])) return completion;
    compat::InstallPipe recovery;
    struct RecoveryReceipt { uint32_t magic = 0x4d535252; uint8_t found = 0, committed = 0, cleaned = 0; } receipt;
    if (compat::fork_pipe_child_process(recovery)) {
        storage::WorkerScope worker(identity.id);
        KModErr result = root_worker(worker);
        std::string kernel_directory;
        if (is_ok(result)) result = storage::current_kernel_directory(kernel_directory);
        bool found = false, committed = false;
        if (is_ok(result)) {
            ModuleMutation mutation(kernel_directory, identity.id);
            result = mutation.error();
            if (is_ok(result)) result = recover_installation_impl(identity.id, found, &identity, &committed);
        }
        receipt.found = found;
        receipt.committed = committed;
        const auto closed = worker.close_checked();
        receipt.cleaned = found && is_ok(result) && is_ok(closed);
        const bool sent = install_transfer(recovery.fd_child_to_parent_write, &receipt, sizeof(receipt), true);
        recovery.close_all();
        maplesu_module_worker_exit(sent ? 0 : 125);
    }
    if (recovery.child_pid <= 0) return completion;
    const bool received = install_transfer(recovery.fd_child_to_parent_read, &receipt, sizeof(receipt), false);
    recovery.close_all();
    int status = 0;
    pid_t waited;
    do { waited = waitpid(recovery.child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (received && receipt.magic == 0x4d535252 && waited == recovery.child_pid &&
        WIFEXITED(status) && WEXITSTATUS(status) == 0 && receipt.found == 1) {
        completion.committed = receipt.committed == 1;
        completion.cleanup_complete = receipt.cleaned == 1;
        completion.cleanup_pending = !completion.cleanup_complete;
        completion.needs_reboot = completion.committed;
    }
    return completion;
}

static KModErr uninstall_module_contents(const char* id, bool callback, bool retire_pending = true) {
    if (!module_paths::valid_id(id)) return KModErr::ERR_MODULE_PARAM;
    std::vector<std::string> pending;
    RETURN_IF_ERROR(module_state::read(module_state::List::PendingRemoval, pending));
    if (std::find(pending.begin(), pending.end(), id) != pending.end()) callback = false;
    KModErr context_error;
    OwnedFd system(open_system_directory(context_error));
    RETURN_IF_ERROR(context_error);
    if (system.get() < 0 && errno != ENOENT) return KModErr::ERR_MODULE_DEL_DIR;
    bool image_present = false;
    std::optional<storage::WorkerScope> restored_worker;
    if (callback && system.get() >= 0)
        RETURN_IF_ERROR(validate_uninstall_callback(system.get(), id, image_present));
    if (image_present) {
        if (!system.close_checked()) return KModErr::ERR_MODULE_DEL_DIR;
        RETURN_IF_ERROR(compat::invoke_module_on_uninstall(std::string(id)));
        restored_worker.emplace(id);
        RETURN_IF_ERROR(root_worker(*restored_worker));
        system.reset(open_system_directory(context_error));
        RETURN_IF_ERROR(context_error);
        if (system.get() < 0 && errno != ENOENT) return KModErr::ERR_MODULE_DEL_DIR;
    }
    // Legacy Removed may describe a valid reinstall; only this intent permits cleanup.
    RETURN_IF_ERROR(module_state::add(module_state::List::PendingRemoval, std::string(id)));
    RETURN_IF_ERROR(module_state::add(module_state::List::Removed, std::string(id)));
    if (system.get() >= 0 && (!delete_from_container(system.get(), "modules", id) ||
        !delete_from_container(system.get(), "storage", id) ||
        !delete_override_from_container(system.get(), "modules_new_state", id) ||
        !delete_override_from_container(system.get(), "modules_new_desc", id)))
        return KModErr::ERR_MODULE_DEL_DIR;
    if (!system.close_checked()) return KModErr::ERR_MODULE_DEL_DIR;
    RETURN_IF_ERROR(compat::unsafe_reset_autorun_bootstrap_safe_flag());
    RETURN_IF_ERROR(module_state::remove(module_state::List::PendingRunOnce, std::string(id)));
    RETURN_IF_ERROR(module_state::remove(module_state::List::FinishedRunOnce, std::string(id)));
    const auto result = retire_pending ? module_state::remove(module_state::List::PendingRemoval, std::string(id)) : KModErr::OK;
    const auto closed = restored_worker ? restored_worker->close_checked() : KModErr::OK;
    return is_failed(result) ? result : closed;
}

KModErr unsafe_uninstall_module_impl(const char* id, bool callback) {
    if (!module_paths::valid_id(id)) return KModErr::ERR_MODULE_PARAM;
    storage::WorkerScope worker(id);
    const KModErr result = [&]() -> KModErr {
        RETURN_IF_ERROR(root_worker(worker));
        std::string kernel_directory;
        RETURN_IF_ERROR(storage::current_kernel_directory(kernel_directory));
        ModuleMutation mutation(kernel_directory, id);
        RETURN_IF_ERROR(mutation.error());
        bool recovered = false;
        RETURN_IF_ERROR(recover_installation_impl(id, recovered));
        return uninstall_module_contents(id, callback);
    }();
    const auto closed = worker.close_checked();
    return is_failed(result) ? result : closed;
}

// parent waits even when its error-code frame could not be read.
KModErr safe_uninstall_module_impl(const char* id) {
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    compat::InstallPipe pipe;
    KModErr err = KModErr::OK;
    if (compat::fork_pipe_child_process(pipe)) {
        err = unsafe_uninstall_module_impl(id, true);
        compat::write_errcode_from_child(pipe, err);
        pipe.close_all();
        maplesu_module_worker_exit(0);
    }
    if (pipe.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (!compat::read_errcode_from_child(pipe, err)) err = KModErr::ERR_READ_CHILD_ERRCODE;
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pipe.child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if ((waited != pipe.child_pid || !WIFEXITED(status) || WEXITSTATUS(status) != 0) &&
        err == KModErr::OK) return KModErr::ERR_READ_CHILD_ERRCODE;
    return err;
}

static KModErr write_module_resource(int root, const std::string& name,
                                    const std::vector<uint8_t>& bytes) {
    std::vector<std::string> components;
    if (!module_paths::relative_components(name, &components)) return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
    OwnedFd parent;
    int current = root;
    for (std::size_t i = 0; i + 1 < components.size(); ++i) {
        const int next = file_utils::open_directory_at(current, components[i].c_str(), true);
        if (next < 0 || !parent.reset(next)) return KModErr::ERR_MODULE_CREATE_DIR;
        current = parent.get();
        if (!file_utils::set_system_access_mode(current)) return KModErr::ERR_SET_FILE_SELINUX;
    }
    const char* leaf = components.back().c_str();
    if (!file_utils::delete_entry_at(current, leaf)) return KModErr::ERR_MODULE_DEL_FILE;
    OwnedFd output(openat(current, leaf, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0777));
    if (output.get() < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    struct stat st{};
    if (fstat(output.get(), &st) != 0 || !S_ISREG(st.st_mode) || st.st_nlink != 1)
        return KModErr::ERR_MODULE_OPEN_FILE;
    for (std::size_t offset = 0; offset < bytes.size();) {
        const std::size_t remaining = std::min(bytes.size() - offset, static_cast<std::size_t>(SSIZE_MAX));
        const ssize_t written = write(output.get(), bytes.data() + offset, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return KModErr::ERR_MODULE_WRITE_FILE;
        offset += static_cast<std::size_t>(written);
    }
    if (!file_utils::set_system_access_mode(output.get())) return KModErr::ERR_SET_FILE_SELINUX;
    if (!sync_directory(output.get()) || !output.close_checked()) return KModErr::ERR_MODULE_WRITE_FILE;
    if (!parent.close_checked()) return KModErr::ERR_MODULE_CREATE_DIR;
    return KModErr::OK;
}

// Directory entries remain implicit; file parents are opened from the module FD.
static KModErr unzip_module_file(const char* archive_path, const maplesu::module::Descriptor& desc, const char* elf_name,
                                int target) {
    OwnedFd module;
    if (!module.reset(dup(target)) || module.get() < 0) return KModErr::ERR_MODULE_OPEN_DIR;
    KModErr err = KModErr::OK;
    std::string archive_name(archive_path);
    std::function<bool(const std::string&, std::vector<uint8_t>&&)> writer =
        [&err, &desc, elf_name, &module](const std::string& name, std::vector<uint8_t>&& bytes) {
        err = write_module_resource(module.get(), name == elf_name ? std::string(desc.id32) : name, bytes);
        return err == KModErr::OK;
    };
    int zip_error = 0;
    zip_t* archive = zip_open(archive_name.c_str(), 0, &zip_error);
    if (!archive) {
        char message[128];
        zip_error_to_str(message, sizeof(message), zip_error, errno);
        std::cerr << "Failed to open archive: " << message << std::endl;
        return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
    }
    zip_file_t* input = nullptr;
    try {
        zip_int64_t count = zip_get_num_entries(archive, 0);
        if (count < 0) { zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE; }
        for (zip_uint64_t i = 0; i != static_cast<zip_uint64_t>(count); ++i) {
            zip_stat_t stat;
            if (zip_stat_index(archive, i, 0, &stat) != 0) {
                std::cerr << "zip_stat_index failed at index " << i << ": " << zip_strerror(archive) << std::endl;
                zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
            }
            const char* raw_name = zip_get_name(archive, i, 0);
            if (!raw_name) {
                std::cerr << "zip_get_name failed at index " << i << ": " << zip_strerror(archive) << std::endl;
                zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
            }
            std::string name(raw_name);
            if (!module_paths::relative_components(name)) {
                zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
            }
            if (!name.empty() && name.back() == '/') continue;
            input = zip_fopen_index(archive, i, 0);
            if (!input) {
                std::cerr << "Failed to open file " << name << ": " << zip_strerror(archive) << std::endl;
                zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
            }
            std::vector<uint8_t> bytes;
            bytes.reserve(stat.size);
            uint8_t buffer[8192];
            zip_int64_t n;
            while ((n = zip_fread(input, buffer, sizeof(buffer))) > 0)
                bytes.insert(bytes.end(), buffer, buffer + n);
            if (n < 0) {
                std::cerr << "Failed to read file " << name << ": " << zip_file_strerror(input) << std::endl;
                zip_fclose(input); input = nullptr;
                zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
            }
            int closed = zip_fclose(input); input = nullptr;
            if (closed != 0) {
                std::cerr << "Failed to close file " << name << ": " << zip_strerror(archive) << std::endl;
                zip_close(archive); return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
            }
            if (!writer(name, std::move(bytes))) { zip_close(archive); return err; }
        }
        if (zip_close(archive) != 0) {
            zip_discard(archive);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }
    } catch (...) {
        if (input) zip_fclose(input);
        zip_close(archive);
        throw;
    }
    if (!module.close_checked()) return KModErr::ERR_MODULE_CREATE_DIR;
    return err;
}

// It never expands variables or interprets metacharacters.
static KModErr run_single_module_command(const char* command) {
    pid_t pid = fork();
    if (pid < 0) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (pid == 0) {
        if (!command || !*command) maplesu_module_worker_exit(127);
        char* text = strdup(command);
        if (!text) maplesu_module_worker_exit(127);
        size_t count = 0, capacity = 8;
        char** args = static_cast<char**>(calloc(capacity + 1, sizeof(char*)));
        if (!args) { free(text); maplesu_module_worker_exit(127); }
        char* p = text;
        while (*p) {
            while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') ++p;
            if (!*p) break;
            if (count == capacity) {
                size_t next = capacity * 2;
                char** resized = static_cast<char**>(realloc(args, (next + 1) * sizeof(char*)));
                if (!resized) { free(args); free(text); maplesu_module_worker_exit(127); }
                memset(resized + capacity, 0, (next + 1 - capacity) * sizeof(char*));
                args = resized; capacity = next;
            }
            args[count++] = p;
            if (*p == '\'' || *p == '"') {
                char quote = *p++;
                args[count - 1] = p;
                while (*p && *p != quote) ++p;
                if (*p) *p++ = 0;
            } else {
                while (*p && *p != ' ' && *p != '\t' && *p != '\n' && *p != '\r') ++p;
                if (*p) *p++ = 0;
            }
        }
        if (count) { args[count] = nullptr; execve(args[0], args, environ); }
        free(args); free(text); maplesu_module_worker_exit(127);
    }
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != pid) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        fprintf(stderr, "[module] installation committed; hotload status=%d\n", status);
        return KModErr::ERR_MODULE_BOOT_SESSION;
    }
    return KModErr::OK;
}

// state-list updates. Only an explicit install rejection invokes uninstall rollback.
KModErr safe_install_module_impl(const char* archive, bool run_once, std::string& reason,
                                InstallCompletion& completion) {
    completion = {};
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    compat::InstallPipe pipe;
    KModErr err = KModErr::OK;
    if (compat::fork_pipe_child_process(pipe)) {
        std::string hotload_command;
        InstallIdentity identity;
        bool identity_sent = false, committed = false, cleanup_complete = false;
        bool cleanup_pending = false;
        bool immediate_start_failed = false;
        storage::WorkerScope worker;
        try {
        err = [&]() -> KModErr {
            RETURN_IF_ERROR(root_worker(worker));
            maplesu::module::Descriptor desc;
            std::string elf_name;
            RETURN_IF_ERROR(maplesu::module::find_module_elf_in_zip(archive, desc, elf_name));
            if (!module_paths::valid_id(desc.id32)) return KModErr::ERR_MODULE_PARAM;
            RETURN_IF_ERROR(worker.select_module(desc.id32));
            maplesu::module::SdkVersion version = compat::current_sdk_version(), minimum;
            sscanf("4.0.5", "%u.%u.%u", &minimum.major, &minimum.minor, &minimum.patch);
            if (version < desc.min_sdk_ver) return KModErr::ERR_MODULE_REQUIRE_MIN_SDK;
            if (desc.min_sdk_ver < minimum) return KModErr::ERR_MODULE_SDK_TOO_OLD;
            std::string kernel_directory;
            RETURN_IF_ERROR(storage::current_kernel_directory(kernel_directory));
            ModuleMutation mutation(kernel_directory, desc.id32);
            RETURN_IF_ERROR(mutation.error());
            bool recovered = false;
            RETURN_IF_ERROR(recover_installation_impl(desc.id32, recovered));
            std::vector<std::string> previous_removed, pending_removal;
            RETURN_IF_ERROR(module_state::read(module_state::List::Removed, previous_removed));
            RETURN_IF_ERROR(module_state::read(module_state::List::PendingRemoval, pending_removal));
            const bool has_pending_removal =
                std::find(pending_removal.begin(), pending_removal.end(), desc.id32) != pending_removal.end();
            const bool replacing_removed = has_pending_removal ||
                std::find(previous_removed.begin(), previous_removed.end(), desc.id32) != previous_removed.end();
            if (has_pending_removal) RETURN_IF_ERROR(uninstall_module_contents(desc.id32, false, false));
            KModErr context_error;
            OwnedFd system(open_system_directory(context_error)), root, transaction;
            RETURN_IF_ERROR(context_error);
            if (system.get() < 0) return KModErr::ERR_MODULE_OPEN_DIR;
            InstallRecord record;
            size_t random_bytes = 0;
            while (random_bytes < sizeof(record.instance)) {
                const ssize_t count = syscall(SYS_getrandom, reinterpret_cast<char*>(record.instance) + random_bytes,
                                              sizeof(record.instance) - random_bytes, 0);
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) return KModErr::ERR_MODULE_WRITE_FILE;
                random_bytes += static_cast<size_t>(count);
            }
            if (!(record.instance[0] | record.instance[1])) return KModErr::ERR_MODULE_WRITE_FILE;
            std::strcpy(identity.id, desc.id32);
            std::memcpy(identity.instance, record.instance, sizeof(record.instance));
            identity_sent = install_transfer(pipe.fd_child_to_parent_write, &identity, sizeof(identity), true);
            if (!identity_sent) return KModErr::ERR_READ_CHILD_ERRCODE;
            KModErr result = [&]() -> KModErr {
                RETURN_IF_ERROR(prepare_installation(system.get(), desc.id32, root, transaction, record));
                OwnedFd image(file_utils::open_directory_at(transaction.get(), kInstallNew[0], false));
                if (image.get() < 0) return KModErr::ERR_MODULE_OPEN_DIR;
                RETURN_IF_ERROR(unzip_module_file(archive, desc, elf_name.c_str(), image.get()));
                if (!sync_directory(image.get()) || !image.close_checked()) return KModErr::ERR_MODULE_WRITE_FILE;
                RETURN_IF_ERROR(publish_installation(system.get(), transaction.get(), desc.id32, record));
                RETURN_IF_ERROR(compat::invoke_module_on_install(std::string(desc.id32), reason));
                RETURN_IF_ERROR(root_worker(worker));
                RETURN_IF_ERROR(compat::unsafe_reset_autorun_bootstrap_safe_flag());
                RETURN_IF_ERROR(module_state::remove(module_state::List::FinishedRunOnce, desc.id32));
                RETURN_IF_ERROR(run_once ? module_state::add(module_state::List::PendingRunOnce, desc.id32)
                                         : module_state::remove(module_state::List::PendingRunOnce, desc.id32));
                const std::string mode_path = (std::filesystem::path(kernel_directory) / "install.mode").string();
                std::string mode;
                const bool mode_read = file_utils::read_text_file(mode_path.c_str(), mode);
                if (!replacing_removed && !(record.old_mask & 1) && mode_read && mode == "hotload")
                    hotload_command = (std::filesystem::path(kernel_directory) / "init").string()
                        + " --load-single-module " + desc.id32;
                if (!mode_read) {
                    if (!reason.empty()) reason += '\n';
                    reason += "Immediate startup mode unavailable; installation retained for the next environment startup.";
                }
                record.committed = 1;
                RETURN_IF_ERROR(write_install_record(transaction.get(), record));
                committed = true;
                return KModErr::OK;
            }();
            // The callback may have changed its credentials. Restore the existing
            // worker Root before touching its own staged generation.
            const KModErr root_result = root_worker(worker);
            KModErr cleanup = root_result;
            if (is_ok(cleanup)) {
                bool found = false, stored_commit = false;
                cleanup = recover_installation_impl(desc.id32, found, &identity, &stored_commit);
                committed = committed || stored_commit;
                cleanup_complete = found && is_ok(cleanup);
            }
            if (is_failed(cleanup)) {
                cleanup_pending = true;
                if (!reason.empty()) reason += '\n';
                reason += "Module staging cleanup pending: " + std::to_string(to_num(cleanup));
            }
            return result;
        }();
        } catch (...) {
            err = KModErr::ERR_MODULE_WRITE_FILE;
            if (module_paths::valid_id(identity.id) && (identity.instance[0] | identity.instance[1])) {
                KModErr cleanup = root_worker(worker);
                std::string kernel_directory;
                if (is_ok(cleanup)) cleanup = storage::current_kernel_directory(kernel_directory);
                if (is_ok(cleanup)) {
                    ModuleMutation mutation(kernel_directory, identity.id);
                    cleanup = mutation.error();
                    if (is_ok(cleanup)) {
                        bool found = false;
                        cleanup = recover_installation_impl(identity.id, found, &identity, &committed);
                        cleanup_complete = found && is_ok(cleanup);
                    }
                }
                cleanup_pending = is_failed(cleanup);
            }
        }
        const auto closed = worker.close_checked();
        if (is_ok(err) && is_failed(closed)) err = closed;
        // Installation and its state are committed before the runtime takes
        // the same module owner. A failed immediate start keeps that install.
        if (committed && is_ok(err) && !cleanup_pending && !hotload_command.empty()) {
            err = run_single_module_command(hotload_command.c_str());
            if (is_failed(err)) {
                immediate_start_failed = true;
                if (!reason.empty()) reason += '\n';
                reason += "Module installation committed; immediate startup failed.";
            }
        }
        if (!identity_sent) install_transfer(pipe.fd_child_to_parent_write, &identity, sizeof(identity), true);
        compat::write_errcode_from_child(pipe, err);
        compat::write_string_from_child(pipe, reason);
        pipe.close_all();
        const int status = committed ?
            (immediate_start_failed ? (cleanup_pending ? kCommittedStartupAndCleanupFailureExit : kCommittedStartupFailureExit)
                                    : (is_failed(err) ? kCommittedDiagnosticExit : (cleanup_pending ? kCommittedCleanupPendingExit :
                                       (hotload_command.empty() ? kCommittedAwaitingBootExit : 0)))) :
            (cleanup_complete ? kRolledBackFailureExit : (cleanup_pending ? kUncommittedCleanupPendingExit : 0));
        maplesu_module_worker_exit(status);
    }
    if (pipe.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;
    InstallIdentity identity;
    const bool identity_received = install_transfer(pipe.fd_child_to_parent_read, &identity, sizeof(identity), false) &&
        identity.magic == 0x4d53494e;
    const bool error_received = identity_received && compat::read_errcode_from_child(pipe, err);
    const bool reason_received = error_received && compat::read_string_from_child(pipe, reason);
    if (!error_received) err = KModErr::ERR_READ_CHILD_ERRCODE;
    else if (!reason_received && is_ok(err)) err = KModErr::ERR_READ_CHILD_STRING;
    pipe.close_all();
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pipe.child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (error_received && reason_received && waited == pipe.child_pid && WIFEXITED(status)) {
        completion.immediate_start_failed = WEXITSTATUS(status) == kCommittedStartupFailureExit &&
            (err == KModErr::ERR_MODULE_BOOT_SESSION || err == KModErr::ERR_READ_CHILD_ERRCODE);
        completion.immediate_start_failed = completion.immediate_start_failed ||
            (WEXITSTATUS(status) == kCommittedStartupAndCleanupFailureExit &&
             (err == KModErr::ERR_MODULE_BOOT_SESSION || err == KModErr::ERR_READ_CHILD_ERRCODE));
        completion.committed = completion.immediate_start_failed ||
            (WEXITSTATUS(status) == kCommittedDiagnosticExit && is_failed(err)) ||
            ((WEXITSTATUS(status) == 0 || WEXITSTATUS(status) == kCommittedCleanupPendingExit ||
              WEXITSTATUS(status) == kCommittedAwaitingBootExit) && is_ok(err));
        completion.cleanup_complete = (WEXITSTATUS(status) == kRolledBackFailureExit && is_failed(err)) ||
            (completion.committed && WEXITSTATUS(status) != kCommittedCleanupPendingExit &&
             WEXITSTATUS(status) != kCommittedDiagnosticExit &&
             WEXITSTATUS(status) != kCommittedStartupAndCleanupFailureExit);
        completion.cleanup_pending = WEXITSTATUS(status) == kUncommittedCleanupPendingExit ||
            (completion.committed && !completion.cleanup_complete);
        completion.needs_reboot = completion.immediate_start_failed ||
            WEXITSTATUS(status) == kCommittedDiagnosticExit ||
            WEXITSTATUS(status) == kCommittedAwaitingBootExit ||
            (completion.committed && !completion.cleanup_complete);
    }
    if ((waited != pipe.child_pid || !WIFEXITED(status) ||
         (WEXITSTATUS(status) != 0 && !completion.committed)) && is_ok(err))
        err = KModErr::ERR_READ_CHILD_ERRCODE;
    if ((!error_received || !reason_received || waited != pipe.child_pid || !WIFEXITED(status)) && identity_received)
        completion = recover_interrupted_install(identity);
    return err;
}

}

KModErr unsafe_uninstall_module(const char* id, bool callback) {
    return unsafe_uninstall_module_impl(id, callback);
}
KModErr cleanup_pending_module(const char* id) {
    return uninstall_module_contents(id, false, false);
}
KModErr recover_installation(const char* id, bool& found) {
    return recover_installation_impl(id, found);
}
KModErr pending_installations(std::vector<std::string>& ids) {
    ids.clear();
    KModErr context_error;
    OwnedFd system(open_system_directory(context_error));
    RETURN_IF_ERROR(context_error);
    if (system.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    OwnedFd root(open_private_directory(system.get(), kInstallDirectory, false));
    if (root.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    DIR* entries = fdopendir(root.get());
    if (!entries) return KModErr::ERR_MODULE_OPEN_DIR;
    root.release();
    KModErr result = KModErr::OK;
    for (;;) {
        errno = 0;
        dirent* entry = readdir(entries);
        if (!entry) { if (errno) result = KModErr::ERR_MODULE_READ_FILE; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (!module_paths::valid_id(entry->d_name)) { result = KModErr::ERR_MODULE_PARAM; break; }
        ids.emplace_back(entry->d_name);
    }
    if (closedir(entries) && is_ok(result)) result = KModErr::ERR_MODULE_READ_FILE;
    return result;
}
KModErr installation_list_paths(const char* id, std::string& image, std::string& state,
                               std::string& description, bool& hidden) {
    hidden = false;
    KModErr context_error;
    OwnedFd system(open_system_directory(context_error));
    RETURN_IF_ERROR(context_error);
    if (system.get() < 0) return KModErr::ERR_MODULE_OPEN_DIR;
    OwnedFd root(open_private_directory(system.get(), kInstallDirectory, false));
    if (root.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    OwnedFd transaction(open_private_directory(root.get(), id, false));
    if (transaction.get() < 0) return errno == ENOENT ? KModErr::OK : KModErr::ERR_MODULE_OPEN_DIR;
    InstallRecord record;
    bool present = false;
    RETURN_IF_ERROR(read_install_record(transaction.get(), record, present));
    if (!present || record.committed) return KModErr::OK;
    if (!(record.old_mask & 1)) { hidden = true; return KModErr::OK; }
    storage::StorageContext context;
    RETURN_IF_ERROR(storage::current_context(context));
    const std::string base = std::string("/data/") + context.locator.system_component + "/" + kInstallDirectory + "/" + id + "/";
    std::string* outputs[] = {&image, nullptr, &state, &description};
    for (const unsigned index : {0u, 2u, 3u}) {
        if (!(record.old_mask & (1u << index))) { outputs[index]->clear(); continue; }
        struct stat backup{};
        if (fstatat(transaction.get(), kInstallOld[index], &backup, AT_SYMLINK_NOFOLLOW) == 0)
            *outputs[index] = base + kInstallOld[index] + (index == 0 ? std::string("/") + id : "");
        else if (errno != ENOENT) return KModErr::ERR_MODULE_READ_FILE;
    }
    return KModErr::OK;
}
KModErr safe_uninstall_module(const char* id) {
    return safe_uninstall_module_impl(id);
}
KModErr safe_install_module(const char* archive, bool run_once, std::string& reason) {
    InstallCompletion completion;
    return safe_install_module_impl(archive, run_once, reason, completion);
}
KModErr safe_install_module(const char* archive, bool run_once, std::string& reason,
                           InstallCompletion& completion) {
    return safe_install_module_impl(archive, run_once, reason, completion);
}
}  // namespace maplesu::module_install

namespace maplesu::sdk {
KModErr unsafe_uninstall_module(const char* id, bool callback) {
    return maplesu::module_install::unsafe_uninstall_module(id, callback);
}
KModErr safe_uninstall_module(const char* id) {
    return maplesu::module_install::safe_uninstall_module(id);
}
KModErr safe_install_module(const char* archive, bool run_once, std::string& reason) {
    return maplesu::module_install::safe_install_module(archive, run_once, reason);
}
}

#include "../compat/module_install_exports_legacy_abi.h"
