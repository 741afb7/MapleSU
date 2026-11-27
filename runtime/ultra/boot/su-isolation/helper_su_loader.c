/* Synchronous environment-owned loader for an already Root helper task. */
#include <dlfcn.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "environment_backend_identity.h"

#ifndef MAPLESU_POLICY_DIRECTORY
#define MAPLESU_POLICY_DIRECTORY "/data"
#endif

static int open_backend(void) {
    int dir = open(MAPLESU_POLICY_DIRECTORY, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0) return -1;
    const char* parts[] = {"adb", "maplesu", "bin"};
    struct stat st;
    for (unsigned i = 0; i < sizeof(parts) / sizeof(parts[0]); ++i) {
        int next = openat(dir, parts[i], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(dir);
        if (next < 0) return -1;
        if (fstat(next, &st) || st.st_uid != 0 || (st.st_mode & 0022)) {
            close(next);
            return -1;
        }
        dir = next;
    }
    int fd = openat(dir, MAPLESU_POLICY_BACKEND_FILE, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    close(dir);
    if (fd < 0) return -1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid != 0 ||
        (st.st_mode & 07777) != 0700 || st.st_nlink != 1 || st.st_size <= 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int backend_matches(int fd) {
    struct stat st;
    unsigned char header[MAPLESU_POLICY_BACKEND_NOTE_OFFSET + sizeof(maplesu_policy_backend_note)];
    _Static_assert(sizeof(header) <= 4096, "backend identity must fit in the first file page");
    if (fstat(fd, &st) || st.st_size != MAPLESU_POLICY_BACKEND_SIZE) return 0;
    size_t have = 0;
    while (have < sizeof(header)) {
        ssize_t count = read(fd, header + have, sizeof(header) - have);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        have += (size_t)count;
    }
    return memcmp(header + MAPLESU_POLICY_BACKEND_NOTE_OFFSET, maplesu_policy_backend_note,
                  sizeof(maplesu_policy_backend_note)) == 0;
}

static void* load_backend(void) {
    if (getuid() != 0) return 0;
    int fd = open_backend();
    if (fd < 0) return 0;
    if (!backend_matches(fd)) { close(fd); return 0; }
    char path[48];
    int length = snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    if (length <= 0 || length >= (int)sizeof(path)) { close(fd); return 0; }
    void* backend = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    close(fd);
    return backend;
}
int64_t maplesu_helper_su_writer_load(const void* packages, const char* module) {
    void* backend = load_backend();
    if (!backend) return -21002;
    int64_t (*entry)(const void*, const char*) =
        (int64_t (*)(const void*, const char*))dlsym(backend, "maplesu_helper_su_write");
    if (!entry) return -21003;
    return entry(packages, module);
}
int64_t maplesu_helper_su_exec_load(void* object, uint64_t control, const char* module) {
    void* backend = load_backend();
    if (!backend) return -21002;
    int64_t (*entry)(void*, uint64_t, const char*) =
        (int64_t (*)(void*, uint64_t, const char*))dlsym(backend, "maplesu_helper_su_exec");
    if (!entry) return -21003;
    return entry(object, control, module);
}
