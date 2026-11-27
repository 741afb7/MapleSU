// Syscall table the boot helper logic uses.
//
// The helper's own logic never calls libc directly: every file, process,
// dynamic-loader and logging action goes through this table.  The Android
// build binds the table to POSIX/bionic; the host test binds it to an
// in-memory filesystem and a recording process runner, so the same transaction
// code is exercised without a device.
//
// Conventions
//   * functions returning int return 0 on success and a negative HELPER_E* on
//     failure, except the descriptors below;
//   * open_path/openat return a non-negative descriptor or a negative error;
//   * read/write return the transferred byte count or a negative error;
//   * names are NUL-terminated UTF-8 path components, never absolute where a
//     descriptor is also supplied.
#ifndef MAPLESU_HELPER_OPS_H
#define MAPLESU_HELPER_OPS_H

typedef long long helper_i64;
typedef unsigned long long helper_u64;
typedef unsigned int helper_u32;
typedef int helper_i32;

// Error values are negative and stable inside the helper.
enum {
    HELPER_OK = 0,
    HELPER_E_NOENT = -2,
    HELPER_E_INTR = -4,
    HELPER_E_IO = -5,
    HELPER_E_ACCES = -13,
    HELPER_E_EXIST = -17,
    HELPER_E_INVAL = -22,
    HELPER_E_MFILE = -24,
    HELPER_E_LOOP = -40,
    HELPER_E_NAMETOOLONG = -36,
    HELPER_E_NOTDIR = -20,
    HELPER_E_ISDIR = -21,
    HELPER_E_ROFS = -30,
    HELPER_E_NOSYS = -38,
    HELPER_E_ENOEXEC = -8,
    HELPER_E_AGAIN = -11,
    HELPER_E_PERM = -1,
    HELPER_E_NOSPC = -28,
    HELPER_E_NOMEM = -12,
    HELPER_E_OVERFLOW = -75,
    HELPER_E_UNKNOWN = -999,
};

#define HELPER_PATH_MAX 512
#define HELPER_NAME_MAX 64
#define HELPER_MODULE_LEN 32
#define HELPER_BOOT_ID_LEN 36
#define HELPER_RECEIPT_LEN 37 /* boot id + '\n', the byte count the receipts hold */

// open_path/openat flag bits.
enum {
    HELPER_OPEN_READ = 1u << 0,
    HELPER_OPEN_DIRECTORY = 1u << 1,
    HELPER_OPEN_WRITE = 1u << 2,
    HELPER_OPEN_CREATE = 1u << 3,
    HELPER_OPEN_EXCL = 1u << 4,
    HELPER_OPEN_NOFOLLOW = 1u << 5,
    HELPER_OPEN_TRUNC = 1u << 6,
    HELPER_OPEN_NONBLOCK = 1u << 7,
    HELPER_OPEN_APPEND = 1u << 8,
};

// fstatat / unlinkat flags.
enum {
    HELPER_AT_SYMLINK_NOFOLLOW = 1u << 0,
    HELPER_AT_REMOVEDIR = 1u << 1,
};

// flock operations.
enum {
    HELPER_LOCK_EX = 1u << 0,
    HELPER_LOCK_NB = 1u << 1,
    HELPER_LOCK_UN = 1u << 2,
};

#define HELPER_SEEK_SET 0
#define HELPER_SEEK_CUR 1

struct helper_stat_info {
    helper_u64 size;
    helper_u32 mode; /* POSIX permission + type bits */
    helper_u32 uid;
    helper_u32 gid;
    helper_u32 nlink;
    helper_u32 is_dir;
    helper_u32 is_reg;
};

struct helper_ops {
    // Process identity.
    int (*getuid)(void);
    int (*geteuid)(void);
    int (*getpid)(void);
    void (*abort_process)(void);

    // Paths and the kernel boot identity.
    int (*read_self_path)(char* out, helper_u32 cap, helper_u32* length);
    int (*read_boot_id)(char* out, helper_u32 cap, helper_u32* length);

    // File descriptors.
    int (*open_path)(const char* path, helper_u32 flags, helper_u32 mode);
    int (*openat)(int dirfd, const char* name, helper_u32 flags, helper_u32 mode);
    int (*fstat)(int fd, struct helper_stat_info* out);
    int (*fstatat)(int dirfd, const char* name, struct helper_stat_info* out, helper_u32 flags);
    long long (*read)(int fd, void* buffer, helper_u32 capacity);
    long long (*write)(int fd, const void* buffer, helper_u32 length);
    long long (*lseek)(int fd, long long offset, int whence);
    int (*close)(int fd);
    int (*fsync)(int fd);
    int (*fchmod)(int fd, helper_u32 mode);
    int (*fchown)(int fd, helper_u32 uid, helper_u32 gid);
    int (*set_xattr)(int fd, const char* name, const char* value, helper_u32 length);
    int (*get_xattr)(int fd, const char* name, char* out, helper_u32 capacity);

    // Directory operations.
    int (*mkdirat)(int dirfd, const char* name, helper_u32 mode);
    int (*renameat)(int olddir, const char* oldname, int newdir, const char* newname);
    int (*unlinkat)(int dirfd, const char* name, helper_u32 flags);
    // Writes up to `capacity` entry names of one directory; returns the count.
    int (*list_dir)(int dirfd, char names[][HELPER_NAME_MAX], helper_u32 capacity,
                    helper_u32* count);

    // Single-instance lock.
    int (*flock_dir)(int fd, helper_u32 operation);

    // Process execution (exec replaces the caller on success).
    int (*execve)(const char* path, char* const argv[], char* const envp[]);
    // Runs "<shell> -c <command>", captures stdout, stores the exit status.
    int (*run_shell)(const char* command, char* out, helper_u32 capacity,
                     helper_u32* length, int* status);

    // Dynamic loading of the paired environment-policy backend.
    void* (*dlopen_fd)(int fd, helper_u32 flags);
    void* (*dlsym)(void* handle, const char* name);

    // One line of operational logging (no formatting, no trailing newline).
    void (*log_line)(const char* text);
};

#endif /* MAPLESU_HELPER_OPS_H */
