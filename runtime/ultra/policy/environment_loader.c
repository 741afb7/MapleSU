/* Synchronous environment-owned loader using current-task authority. */
#include <dlfcn.h>
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

int maplesu_environment_policy_load(void) {
    if (getuid() != 0 || geteuid() != 0) return 1;
    int fd = open_backend();
    if (fd < 0) return 2;
    // Publication verifies the complete SHA-256 before rename. Check the paired
    // inode's build identity here before any DSO constructor can call the SDK.
    if (!backend_matches(fd)) { close(fd); return 7; }
    char path[48];
    int length = snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    if (length <= 0 || length >= (int)sizeof(path)) { close(fd); return 3; }
    void* backend = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    close(fd);
    if (!backend) return 4;
    int (*prepare)(void) = (int (*)(void))dlsym(backend, "maplesu_environment_policy_start");
    if (!prepare) { dlclose(backend); return 5; }
    // Retain the image even on a backend failure: a hook may already exist.
    // There is no worker, background retry, credential argv or per-page query.
    return prepare() == 0 ? 0 : 6;
}
