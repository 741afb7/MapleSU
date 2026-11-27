#include "maplesu_parasite.h"
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"
#include "maplesu_sdk/maplesu_su_auth.h"
#include "parasite_payloads.h"
#include "maplesu_request_private.h"
#include "../../maplesu-sdk/sdk/src/module/su_redirect_tu/su_uid_profile_private.h"
#include <set>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <fstream>
#include <limits.h>
#include <map>
#include <poll.h>
#include <sstream>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <vector>

namespace kernel_root {
int parasite_check_so_link(const char*, const char*);
int parasite_start_link_so(const char*, const char*);
}

#ifndef MAPLESU_PARASITE_PROC_ROOT
#define MAPLESU_PARASITE_PROC_ROOT "/proc"
#endif
#ifndef MAPLESU_PARASITE_PM
#define MAPLESU_PARASITE_PM "/system/bin/pm"
#endif
#ifndef MAPLESU_PARASITE_START
#define MAPLESU_PARASITE_START "/data/adb/maplesu/recovery/parasite-start.sh"
#endif
#ifndef MAPLESU_PARASITE_TIMEOUT_MS
#define MAPLESU_PARASITE_TIMEOUT_MS 30000
#endif

namespace {
using namespace kernel_root;
using Libraries = std::map<std::string, AppDynlibStatus>;
constexpr const char* kAppLoader = "libmaplesu_parasite.so";
constexpr const char* kSuLoader = "libmaplesu_su_env.so";
constexpr const char* kDirectorySlot = "MAPLESU_SU_ENV_DIRECTORY_SLOT_5_1_0";
constexpr size_t kMaxLibraries = 512;

struct Fd {
    int value;
    explicit Fd(int fd) : value(fd) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};

bool transfer(int fd, void* buffer, size_t size, bool output) {
    auto* bytes = static_cast<char*>(buffer);
    while (size) {
        ssize_t n = output ? write(fd, bytes, size) : read(fd, bytes, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        bytes += n;
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool identifier(const char* value) {
    if (!value || !*value || strnlen(value, 256) >= 256) return false;
    bool colon = false;
    for (const char* p = value; *p; ++p) {
        if (*p == ':' && !colon && p != value && p[1]) { colon = true; continue; }
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_')) return false;
    }
    return true;
}

std::string canonical(const std::string& path) {
    char resolved[PATH_MAX];
    return realpath(path.c_str(), resolved) ? std::string(resolved) : std::string();
}

bool elf64(int fd) {
    Elf64_Ehdr header{};
    return pread(fd, &header, sizeof(header), 0) == sizeof(header) &&
        memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 && header.e_ident[EI_CLASS] == ELFCLASS64 &&
        header.e_ident[EI_DATA] == ELFDATA2LSB && header.e_machine == EM_AARCH64 &&
        header.e_type == ET_DYN && header.e_ehsize == sizeof(header);
}

bool elf64(const std::string& path) {
    Fd fd(open(path.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    struct stat st{};
    return fd.value >= 0 && fstat(fd.value, &st) == 0 && S_ISREG(st.st_mode) && elf64(fd.value);
}

std::string app_directory(const char* cmdline) {
    std::string package(cmdline);
    package = package.substr(0, package.find(':'));
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) != 0) return {};
    Fd input(pipes[0]), output(pipes[1]);
    pid_t child = fork();
    if (child < 0) return {};
    if (child == 0) {
        dup2(output.value, STDOUT_FILENO);
        close(input.value);
        close(output.value);
        execl(MAPLESU_PARASITE_PM, "pm", "path", package.c_str(), static_cast<char*>(nullptr));
        _exit(127);
    }
    close(output.value); output.value = -1;
    std::string text;
    char buffer[1024];
    ssize_t n;
    while ((n = read(input.value, buffer, sizeof(buffer))) != 0) {
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (text.size() + n > 65536) { kill(child, SIGKILL); break; }
        text.append(buffer, static_cast<size_t>(n));
    }
    int status = 0;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) return {};
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        if (line.compare(0, 9, "package:/") != 0) continue;
        std::string apk = line.substr(8);
        if (!apk.empty() && apk.back() == '\r') apk.pop_back();
        if (apk.size() < 9 || apk.substr(apk.size() - 9) != "/base.apk") continue;
        return canonical(apk.substr(0, apk.size() - 9));
    }
    return {};
}

ParasiteError precheck(const char* cmdline, Libraries& result) {
    std::string app = app_directory(cmdline);
    if (app.empty()) return PARASITE_APP_DIR;
    DIR* proc = opendir(MAPLESU_PARASITE_PROC_ROOT);
    if (!proc) return PARASITE_PROCESS;
    bool running = false, arm32 = false;
    std::map<std::string, bool> mapped;
    while (auto* entry = readdir(proc)) {
        if (!*entry->d_name || strspn(entry->d_name, "0123456789") != strlen(entry->d_name)) continue;
        std::string directory = std::string(MAPLESU_PARASITE_PROC_ROOT) + "/" + entry->d_name;
        std::ifstream args(directory + "/cmdline", std::ios::binary);
        std::string first;
        std::getline(args, first, '\0');
        if (first != cmdline) continue;
        running = true;
        std::ifstream maps(directory + "/maps");
        std::string line;
        while (std::getline(maps, line)) {
            auto slash = line.find('/');
            if (slash == std::string::npos) continue;
            std::string path = line.substr(slash);
            if (path.compare(0, app.size() + 1, app + "/") != 0) continue;
            if (path.find("/lib/arm/") != std::string::npos) arm32 = true;
            mapped[path] = true;
        }
    }
    closedir(proc);
    if (!running) return PARASITE_PROCESS;
    if (arm32) return PARASITE_32BIT;
    std::string lib = canonical(app + "/lib/arm64");
    if (lib.empty() || lib.compare(0, app.size() + 1, app + "/") != 0) return PARASITE_OK;
    DIR* files = opendir(lib.c_str());
    if (!files) return PARASITE_APP_DIR;
    while (auto* entry = readdir(files)) {
        std::string name(entry->d_name);
        if (name.size() < 4 || name.substr(name.size() - 3) != ".so" ||
            name == kAppLoader || name == kSuLoader) continue;
        std::string path = lib + "/" + name;
        if (!elf64(path)) continue;
        result[path] = mapped.count(path) ? Running : NotRunning;
        if (result.size() > kMaxLibraries) { closedir(files); return PARASITE_IO; }
    }
    closedir(files);
    return PARASITE_OK;
}

// The imported patch engine keeps process-global option state. Every invocation gets
// its own fresh child, including print-before-add and verify-after-add.
int patch(const std::string& path, const char* name, bool add) {
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        int result = add ? parasite_start_link_so(path.c_str(), name) :
            parasite_check_so_link(path.c_str(), name);
        _exit(result >= 0 && result <= 255 ? result : 1);
    }
    int status = 0;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    return waited == child && WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

bool same_files(int left, int right) {
    char a[16384], b[16384];
    off_t offset = 0;
    for (;;) {
        ssize_t n = pread(left, a, sizeof(a), offset);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 || pread(right, b, sizeof(b), offset) != n) return false;
        if (n == 0) return true;
        if (memcmp(a, b, static_cast<size_t>(n))) return false;
        offset += n;
    }
}

bool metadata(int from, int to, const struct stat& st) {
    if (fchown(to, st.st_uid, st.st_gid) != 0 || fchmod(to, st.st_mode & 0777) != 0) return false;
    char context[4096];
    ssize_t size = fgetxattr(from, "security.selinux", context, sizeof(context));
    if (size < 0) {
#ifdef MAPLESU_PARASITE_HOST_TEST
        return errno == ENODATA || errno == ENOTSUP;
#else
        return false;
#endif
    }
    return size > 0 && fsetxattr(to, "security.selinux", context, static_cast<size_t>(size), 0) == 0;
}

bool copy_file(int source, int destination) {
    char buffer[16384];
    off_t offset = 0;
    for (;;) {
        ssize_t n = pread(source, buffer, sizeof(buffer), offset);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) return false;
        if (n == 0) return true;
        if (!transfer(destination, buffer, static_cast<size_t>(n), true)) return false;
        offset += n;
    }
}

ParasiteError owner_target_authorized(const char* cmdline) {
    std::set<uint32_t> targets;
    DIR* proc = opendir(MAPLESU_PARASITE_PROC_ROOT);
    if (!proc) return PARASITE_PROCESS;
    while (auto* entry = readdir(proc)) {
        if (!*entry->d_name || strspn(entry->d_name, "0123456789") != strlen(entry->d_name)) continue;
        const std::string path = std::string(MAPLESU_PARASITE_PROC_ROOT) + "/" + entry->d_name;
        Fd process(open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (process.value < 0) continue; // Processes may exit while enumerating.
        Fd command(openat(process.value, "cmdline", O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
        if (command.value < 0) continue;
        char text[256]{};
        const auto count = read(command.value, text, sizeof(text));
        if (count <= 0 || !memchr(text, 0, static_cast<size_t>(count)) || cmdline != std::string(text)) continue;
        struct stat identity{};
        if (fstat(process.value, &identity) != 0) { closedir(proc); return PARASITE_PROCESS; }
        targets.insert(static_cast<uint32_t>(identity.st_uid));
    }
    closedir(proc);
    if (targets.empty()) return PARASITE_PROCESS;
    std::set<uint32_t> allowed;
    thread_local std::set<uint32_t>* captured = nullptr;
    struct Reset {
        std::set<uint32_t>* output;
        ~Reset() { captured = output; }
    } reset{captured};
    captured = &allowed;
    const auto error = maplesu::su_auth::get_uid_profiles_with_cb([](const maplesu::su_auth::UidProfileV1* row) {
        if (row && captured && row->allow) captured->insert(row->uid);
    });
    if (is_failed(error)) return static_cast<ParasiteError>(error);
    for (const auto uid : targets) if (!allowed.count(uid)) return -31003;
    return PARASITE_OK;
}

ParasiteError implant(const char* cmdline, const char* original, const char* su_dir) {
    Libraries eligible;
    auto error = precheck(cmdline, eligible);
    if (error) return error;
    const std::string path = canonical(original);
    if (path != original || !eligible.count(path)) return PARASITE_ORIGINAL;
    if (!su_dir) {
        struct stat st{};
        if (lstat(MAPLESU_PARASITE_START, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != 0 ||
            (st.st_mode & 0022) != 0) return PARASITE_RECOVERY_MISSING;
        const auto authorized = owner_target_authorized(cmdline);
        if (authorized) return authorized;
    } else {
        if (canonical(su_dir) != su_dir || strchr(su_dir, ':')) return PARASITE_PARAM;
        struct stat st{};
        if (lstat((std::string(su_dir) + "/su").c_str(), &st) != 0 || !S_ISREG(st.st_mode) ||
            st.st_uid != 0 || (st.st_mode & 0022) || !(st.st_mode & 0111)) return PARASITE_PAYLOAD;
    }
    const auto slash = path.find_last_of('/');
    const auto directory = path.substr(0, slash);
    const auto filename = path.substr(slash + 1);
    Fd dir(open(directory.c_str(), O_DIRECTORY | O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    if (dir.value < 0) return PARASITE_IO;
    Fd lock(openat(dir.value, ".maplesu-parasite.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600));
    struct stat lock_st{};
    if (lock.value < 0 || fstat(lock.value, &lock_st) != 0 || !S_ISREG(lock_st.st_mode) ||
        lock_st.st_uid != 0 || (lock_st.st_mode & 0077) || flock(lock.value, LOCK_EX | LOCK_NB) != 0)
        return PARASITE_CONFLICT;
    Fd source(openat(dir.value, filename.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    struct stat st{};
    if (source.value < 0 || fstat(source.value, &st) != 0 || !S_ISREG(st.st_mode) || !elf64(source.value))
        return PARASITE_NOT_ELF64;
    const char* loader = su_dir ? kSuLoader : kAppLoader;
    std::vector<unsigned char> payload;
    if (su_dir) {
        payload.assign(maplesu_su_payload, maplesu_su_payload + sizeof(maplesu_su_payload));
        auto begin = std::search(payload.begin(), payload.end(), kDirectorySlot, kDirectorySlot + strlen(kDirectorySlot));
        if (begin == payload.end() || payload.end() - begin < 1024 || strlen(su_dir) >= 1024 ||
            std::search(begin + 1, payload.end(), kDirectorySlot, kDirectorySlot + strlen(kDirectorySlot)) != payload.end())
            return PARASITE_PAYLOAD;
        std::fill(begin, begin + 1024, 0);
        memcpy(&*begin, su_dir, strlen(su_dir));
    } else payload.assign(maplesu_app_payload, maplesu_app_payload + sizeof(maplesu_app_payload));
    bool loader_present = false;
    {
        Fd existing(openat(dir.value, loader, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
        if (existing.value >= 0) {
            struct stat ls{};
            if (fstat(existing.value, &ls) != 0 || !S_ISREG(ls.st_mode) || ls.st_size != static_cast<off_t>(payload.size()))
                return PARASITE_CONFLICT;
            std::vector<unsigned char> bytes(payload.size());
            if (!transfer(existing.value, bytes.data(), bytes.size(), false) || bytes != payload) return PARASITE_CONFLICT;
            loader_present = true;
        } else if (errno != ENOENT) return PARASITE_CONFLICT;
    }
    int linked = patch(path, loader, false);
    if (linked != 0 && linked != 2) return PARASITE_VERIFY;
    const std::string backup = filename + ".maplesu-parasite.orig";
    {
        Fd saved(openat(dir.value, backup.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
        if (saved.value >= 0) {
            struct stat bs{};
            if (fstat(saved.value, &bs) != 0 || !S_ISREG(bs.st_mode) || bs.st_uid != st.st_uid || !elf64(saved.value))
                return PARASITE_CONFLICT;
            // An interrupted first install may reuse its exact original backup. A
            // changed host must never silently inherit an unrelated rollback image.
            if (linked != 0 && patch(path, su_dir ? kAppLoader : kSuLoader, false) != 0 &&
                !same_files(source.value, saved.value)) return PARASITE_CONFLICT;
        } else {
            if (errno != ENOENT || linked == 0) return PARASITE_CONFLICT;
            Fd created(openat(dir.value, backup.c_str(), O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
            if (created.value < 0) return PARASITE_CONFLICT;
            if (!copy_file(source.value, created.value) || !metadata(source.value, created.value, st) ||
                !same_files(source.value, created.value) || fsync(created.value) != 0 || fsync(dir.value) != 0) {
                unlinkat(dir.value, backup.c_str(), 0);
                return PARASITE_IO;
            }
        }
    }
    if (linked == 0) return loader_present ? PARASITE_OK : PARASITE_PAYLOAD;
    const std::string temp = filename + ".maplesu-" + std::to_string(getpid()) + ".tmp";
    const std::string loader_temp = std::string(loader) + "." + std::to_string(getpid()) + ".tmp";
    struct Cleanup {
        int dir;
        const std::string& temp;
        const std::string& loader_temp;
        ~Cleanup() { unlinkat(dir, temp.c_str(), 0); unlinkat(dir, loader_temp.c_str(), 0); }
    } cleanup{dir.value, temp, loader_temp};
    Fd staging(openat(dir.value, temp.c_str(), O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (staging.value < 0 || !copy_file(source.value, staging.value)) return PARASITE_IO;
    const std::string staged_path = directory + "/" + temp;
    if (patch(staged_path, loader, true) != 0) return PARASITE_LINK;
    if (patch(staged_path, loader, false) != 0 || !elf64(staging.value)) return PARASITE_VERIFY;
    if (!metadata(source.value, staging.value, st) || fsync(staging.value) != 0) return PARASITE_CONTEXT;
    if (!loader_present) {
        Fd output(openat(dir.value, loader_temp.c_str(), O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
        struct stat mode = st; mode.st_mode = 0755;
        if (output.value < 0 || !transfer(output.value, payload.data(), payload.size(), true) ||
            !elf64(output.value) || !metadata(source.value, output.value, mode) || fsync(output.value) != 0)
            return PARASITE_PAYLOAD;
        // linkat is an atomic no-replace publication. Never overwrite another installation.
        if (linkat(dir.value, loader_temp.c_str(), dir.value, loader, 0) != 0 || fsync(dir.value) != 0)
            return PARASITE_IO;
    }
    Fd current(openat(dir.value, filename.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    struct stat now{};
    if (current.value < 0 || fstat(current.value, &now) != 0 || now.st_ino != st.st_ino ||
        now.st_dev != st.st_dev || now.st_size != st.st_size ||
        now.st_mtim.tv_sec != st.st_mtim.tv_sec || now.st_mtim.tv_nsec != st.st_mtim.tv_nsec ||
        now.st_ctim.tv_sec != st.st_ctim.tv_sec || now.st_ctim.tv_nsec != st.st_ctim.tv_nsec ||
        !same_files(source.value, current.value))
        return PARASITE_CONFLICT;
    if (renameat(dir.value, temp.c_str(), dir.value, filename.c_str()) != 0 || fsync(dir.value) != 0)
        return PARASITE_IO;
    return PARASITE_OK;
}

struct Reply { int64_t error; uint32_t count; uint32_t reserved = 0; };
ParasiteError invoke(const char* cmdline, const char* so, const char* su,
                    void (*callback)(const char*, AppDynlibStatus)) {
    const auto auth = kernel_module::maplesu_request_auth_state();
    if (!kernel_module::maplesu_request_auth_authenticated(auth) || !identifier(cmdline) ||
        (so && (so[0] != '/' || strnlen(so, PATH_MAX) == PATH_MAX)) ||
        (su && (su[0] != '/' || strnlen(su, 1024) == 1024))) return PARASITE_PARAM;
    const auto admitted = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admitted)) return static_cast<ParasiteError>(admitted);
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) != 0) return PARASITE_CHILD;
    Fd input(pipes[0]), output(pipes[1]);
    pid_t child = fork();
    if (child < 0) return PARASITE_CHILD;
    if (child == 0) {
        setpgid(0, 0);
        close(input.value);
        Libraries libraries;
        ParasiteError error = PARASITE_CHILD;
        try {
            error = static_cast<ParasiteError>(maplesu::sdk::get_root());
            if (!error) error = so ? implant(cmdline, so, su) : precheck(cmdline, libraries);
        } catch (...) { error = PARASITE_CHILD; libraries.clear(); }
        const auto closed = kernel_module::maplesu_request_owner_close();
        if (!error && is_failed(closed)) error = static_cast<ParasiteError>(closed);
        Reply reply{error, error ? 0 : static_cast<uint32_t>(libraries.size())};
        bool sent = transfer(output.value, &reply, sizeof(reply), true);
        if (!error) for (const auto& item : libraries) {
            uint32_t fields[] = {static_cast<uint32_t>(item.first.size()), static_cast<uint32_t>(item.second)};
            sent = sent && transfer(output.value, fields, sizeof(fields), true) &&
                transfer(output.value, const_cast<char*>(item.first.data()), item.first.size(), true);
        }
        _exit(sent ? 0 : 1);
    }
    setpgid(child, child);
    close(output.value); output.value = -1;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(MAPLESU_PARASITE_TIMEOUT_MS);
    std::vector<char> bytes;
    bool eof = false;
    while (std::chrono::steady_clock::now() < deadline) {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        pollfd fd{input.value, POLLIN, 0};
        int ready = poll(&fd, 1, static_cast<int>(std::max<int64_t>(left, 0)));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) break;
        char data[8192];
        ssize_t n = read(input.value, data, sizeof(data));
        if (n < 0 && errno == EINTR) continue;
        if (n == 0) { eof = true; break; }
        if (n < 0 || bytes.size() + n > sizeof(Reply) + kMaxLibraries * (PATH_MAX + 8)) break;
        bytes.insert(bytes.end(), data, data + n);
    }
    if (!eof) { kill(-child, SIGKILL); kill(child, SIGKILL); }
    int status = 0;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (!eof || waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0 || bytes.size() < sizeof(Reply))
        return PARASITE_CHILD;
    Reply reply{};
    memcpy(&reply, bytes.data(), sizeof(reply));
    if (reply.count > kMaxLibraries || (reply.error && (reply.count || bytes.size() != sizeof(Reply)))) return PARASITE_CHILD;
    Libraries libraries;
    size_t offset = sizeof(reply);
    for (uint32_t i = 0; i < reply.count; ++i) {
        uint32_t fields[2];
        if (bytes.size() - offset < sizeof(fields)) return PARASITE_CHILD;
        memcpy(fields, bytes.data() + offset, sizeof(fields)); offset += sizeof(fields);
        if (!fields[0] || fields[0] >= PATH_MAX || fields[1] < Running || fields[1] > NotRunning ||
            bytes.size() - offset < fields[0]) return PARASITE_CHILD;
        std::string path(bytes.data() + offset, fields[0]); offset += fields[0];
        if (path[0] != '/' || path.find('\0') != std::string::npos || libraries.count(path)) return PARASITE_CHILD;
        libraries[path] = static_cast<AppDynlibStatus>(fields[1]);
    }
    if (offset != bytes.size()) return PARASITE_CHILD;
    for (const auto& item : libraries) callback(item.first.c_str(), item.second);
    return static_cast<ParasiteError>(reply.error);
}
}

namespace kernel_root {
ParasiteError parasite_precheck_app_with_cb(const char* cmdline,
                                          void (*callback)(const char*, AppDynlibStatus)) {
    if (!callback) return PARASITE_PARAM;
    return invoke(cmdline, nullptr, nullptr, callback);
}
ParasiteError parasite_implant_app(const char* cmdline, const char* so) {
    if (!so) return PARASITE_PARAM;
    return invoke(cmdline, so, nullptr, nullptr);
}
ParasiteError parasite_implant_su_env(const char* cmdline, const char* so, const char* su) {
    if (!so || !su) return PARASITE_PARAM;
    return invoke(cmdline, so, su, nullptr);
}
}
