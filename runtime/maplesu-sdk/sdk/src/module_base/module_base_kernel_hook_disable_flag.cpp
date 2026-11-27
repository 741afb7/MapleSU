// module base kernel hook disable flag implementation.
//
#include "kernel_module_kit_umbrella.h"
#include "allocation_history_private.h"
#include "maplesu_storage_private.h"
#include "maplesu_request_private.h"

#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <filesystem>
#include <limits>
#include <string>
#include <system_error>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

#include "private_maple_runtime.h"

namespace kernel_module {
namespace detail_hook_disable_flag_history {

// The history lives under the runtime data directory when it exists, otherwise
// under /dev.
static KModErr get_hook_disable_flag_history_paths(std::string& start, std::string& next,
                                             std::string& end) {
    std::string directory;
    const KModErr error = maplesu::storage::current_kernel_directory(directory);
    if (error != KModErr::OK) return error;
    if (maplesu_request_auth_legacy(maplesu_request_auth_state())) {
        std::error_code filesystem_error;
        if (!std::filesystem::exists(directory, filesystem_error) ||
            !std::filesystem::is_directory(directory, filesystem_error)) directory = "/dev/";
    }
    start = directory + "khook_disable_flag_start.1c7e71a8f42f592560e28186c979004a";
    next = directory + "khook_disable_flag_next.cb1a2d24909b86dd1c7e42ee3994c3b2";
    end = directory + "khook_disable_flag_end.3b5d7957e9c7be97883a3e420dd96e46";
    return KModErr::OK;
}

static int close_history_file(int fd, int error) {
    const int close_error = close(fd) != 0 ? errno : 0;
    if (!error) error = close_error;
    if (error) errno = error;
    return error;
}

static int check_history_file(int fd, struct stat& info) {
    if (fstat(fd, &info) != 0) return errno;
    return S_ISREG(info.st_mode) && info.st_uid == 0 && info.st_nlink == 1 ? 0 : EPERM;
}

static int write_history_word(int fd, uint64_t value, bool& publication_attempted) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(&value);
    size_t done = 0;
    while (done < sizeof(value)) {
        publication_attempted = true;
        const ssize_t count = write(fd, bytes + done, sizeof(value) - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return count < 0 ? errno : EIO;
        done += static_cast<size_t>(count);
    }
    return ftruncate(fd, sizeof(value)) != 0 ? errno : 0;
}

// A missing or invalidated word starts a new history; malformed entries are errors.
static bool read_file_u64(const char* file_path, uint64_t& out_value, bool& exists) {
    exists = true;
    const int fd = open(file_path, O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) {
        exists = errno != ENOENT;
        return !exists;
    }
    struct stat info{};
    int error = check_history_file(fd, info);
    if (!error && (info.st_mode & 0022) != 0) {
        bool publication_attempted = false;
        error = write_history_word(fd, 0, publication_attempted);
        if (!error && fchmod(fd, 0600) != 0) error = errno;
        error = close_history_file(fd, error);
        if (!error) exists = false;
        return !error;
    }
    if (!error && info.st_size != sizeof(uint64_t)) error = EINVAL;
    if (!error && (info.st_mode & 07777) != 0600 && fchmod(fd, 0600) != 0) error = errno;
    uint64_t value = 0;
    auto* bytes = reinterpret_cast<unsigned char*>(&value);
    size_t done = 0;
    while (!error && done < sizeof(value)) {
        const ssize_t count = read(fd, bytes + done, sizeof(value) - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { error = count < 0 ? errno : EIO; break; }
        done += static_cast<size_t>(count);
    }
    if (!error) {
        unsigned char extra = 0;
        ssize_t count;
        do { count = read(fd, &extra, 1); } while (count < 0 && errno == EINTR);
        if (count) error = count < 0 ? errno : EINVAL;
    }
    error = close_history_file(fd, error);
    if (!error) out_value = value;
    return !error;
}

// Qualify the opened inode before writing or shortening a prior history.
static KModErr write_file_u64(const char* file_path, uint64_t value,
                              bool& publication_attempted) {
    const int fd = open(file_path, O_WRONLY | O_CREAT | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
    if (fd < 0) return KModErr::ERR_MODULE_WRITE_FILE;
    struct stat info{};
    int error = check_history_file(fd, info);
    KModErr failure = KModErr::ERR_MODULE_WRITE_FILE;
    if (!error && (fchmod(fd, 0600) != 0 ||
        fsetxattr(fd, "security.selinux", "u:object_r:kernel:s0", sizeof("u:object_r:kernel:s0"), 0) != 0)) {
        error = errno;
        failure = KModErr::ERR_SET_FILE_SELINUX;
    }
    if (!error) error = write_history_word(fd, value, publication_attempted);
    return close_history_file(fd, error) ? failure : KModErr::OK;
}

// Preserve the three eight-byte records and their per-file kernel label.
static KModErr write_history_files(uint64_t start, uint64_t next, uint64_t end,
                                   bool& publication_attempted) {
    std::string start_path, next_path, end_path;
    const KModErr path_error = get_hook_disable_flag_history_paths(start_path, next_path, end_path);
    if (path_error != KModErr::OK) return path_error;
    KModErr error = write_file_u64(start_path.c_str(), start, publication_attempted);
    if (error == KModErr::OK) error = write_file_u64(next_path.c_str(), next, publication_attempted);
    if (error == KModErr::OK) error = write_file_u64(end_path.c_str(), end, publication_attempted);
    return error;
}

}  // namespace detail_hook_disable_flag_history

// Reserves one hook-disable history slot: the slot counter advances by one and
// its previous value is returned through out_flag.
KModErr acquire_kernel_hook_disable_flag(unsigned long& out_flag) {
    using namespace detail_hook_disable_flag_history;
    uint64_t start = 0;
    uint64_t next = 0;
    uint64_t end = 0;
    if (getuid() != 0) {
        const KModErr err = default_modules::module_root::set_current_root(true);
        if (err != KModErr::OK) {
            return err;
        }
    }
    {
        std::string start_path, next_path, end_path;
        const KModErr path_error = get_hook_disable_flag_history_paths(start_path, next_path, end_path);
        if (path_error != KModErr::OK) return path_error;
        bool start_exists = false, next_exists = false, end_exists = false;
        if (!read_file_u64(start_path.c_str(), start, start_exists) ||
            !read_file_u64(next_path.c_str(), next, next_exists) ||
            !read_file_u64(end_path.c_str(), end, end_exists))
            return KModErr::ERR_MODULE_READ_FILE;
        if (!start_exists || !next_exists || !end_exists) start = next = end = 0;
    }
    constexpr uint64_t history_size = 12288;
    constexpr uint64_t address_max = std::numeric_limits<uint64_t>::max();
    bool new_history = false;
    bool publication_attempted = false;
    const auto fail_unpublished = [&](KModErr failure) {
        const int saved_errno = errno;
        if (new_history && !publication_attempted && start != 0) (void)free_kernel_mem(start);
        errno = saved_errno;
        return failure;
    };
    if (start == 0 && next == 0 && end == 0) {
        KModErr err = alloc_kernel_mem(history_size, start);
        if (err != KModErr::OK) {
            return err;
        }
        new_history = true;
        if (start == 0 || start > address_max - history_size) {
            errno = start == 0 ? EINVAL : EOVERFLOW;
            return fail_unpublished(KModErr::ERR_MODULE_ALLOC_MEM);
        }
        err = fill00_kernel_mem(start, history_size, KernMemProt::KMP_RW);
        if (err != KModErr::OK) {
            return fail_unpublished(err);
        }
        end = start + history_size;
        next = start;
    } else if (start == 0 || next == 0 || end == 0 ||
               start > address_max - history_size || end != start + history_size ||
               next < start || next > end) {
        errno = EINVAL;
        return KModErr::ERR_MODULE_READ_FILE;
    }
    if (next == end) {
        return KModErr::ERR_MODULE_HOOK_DISABLE_FLAG_NOT_ENOUGH;
    }
    if (next == address_max) {
        errno = EOVERFLOW;
        return fail_unpublished(new_history ? KModErr::ERR_MODULE_ALLOC_MEM : KModErr::ERR_MODULE_READ_FILE);
    }
    const uint64_t next_flag = next + 1;
    if (getuid() != 0) {
        const KModErr err = default_modules::module_root::set_current_root(true);
        if (err != KModErr::OK) {
            return fail_unpublished(err);
        }
    }
    const KModErr err = write_history_files(start, next_flag, end, publication_attempted);
    if (err != KModErr::OK) {
        return fail_unpublished(err);
    }
    out_flag = static_cast<unsigned long>(next);
    return KModErr::OK;
}

}  // namespace kernel_module

KModErr maplesu::allocation_history::reset_hook_flags() {
    bool publication_attempted = false;
    return kernel_module::detail_hook_disable_flag_history::write_history_files(0, 0, 0, publication_attempted);
}
