#include "path_injection.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <climits>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#ifndef MAPLESU_PATH_PROC_ROOT
#define MAPLESU_PATH_PROC_ROOT "/proc"
#endif

namespace {
constexpr int kRejected = -9976;
constexpr int kLibcMissing = -9973;
constexpr int kFileInvalid = -9962;
constexpr int kFileOpen = -9964;
constexpr size_t kMaxPath = 32768;
using Clock = std::chrono::steady_clock;

struct Fd {
    int value;
    explicit Fd(int descriptor) : value(descriptor) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};

bool read_text(int directory, const char* name, std::string& output, size_t limit) {
    Fd input(openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    if (input.value < 0) return false;
    output.clear();
    char bytes[4096];
    for (;;) {
        const ssize_t count = read(input.value, bytes, sizeof(bytes));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 || (count > 0 && output.size() + count > limit)) return false;
        if (count == 0) return true;
        output.append(bytes, static_cast<size_t>(count));
    }
}

bool package_name(const char* text) {
    if (!text || !*text || strnlen(text, 256) == 256) return false;
    for (const char* at = text; *at; ++at) {
        if (!((*at >= 'a' && *at <= 'z') || (*at >= 'A' && *at <= 'Z') ||
              (*at >= '0' && *at <= '9') || *at == '_' || *at == '.')) return false;
    }
    return true;
}

bool matches_package(const std::string& command, const char* package) {
    const size_t length = strlen(package);
    return command == package || (command.size() > length + 1 &&
        command.compare(0, length, package) == 0 && command[length] == ':');
}

bool process_identity(int directory, const char* package, uid_t expected_uid, uint64_t& start) {
    std::string text;
    if (!read_text(directory, "cmdline", text, 4096)) return false;
    const auto end = text.find('\0');
    if (end == std::string::npos || !matches_package(text.substr(0, end), package)) return false;
    if (!read_text(directory, "status", text, 65536)) return false;
    std::istringstream lines(text);
    std::string line;
    bool uid_matches = false;
    while (std::getline(lines, line)) {
        if (line.compare(0, 4, "Uid:") != 0) continue;
        unsigned long long real, effective, saved, filesystem;
        if (sscanf(line.c_str() + 4, "%llu %llu %llu %llu", &real, &effective, &saved, &filesystem) != 4)
            return false;
        uid_matches = real == expected_uid && effective == expected_uid &&
            saved == expected_uid && filesystem == expected_uid;
        break;
    }
    if (!uid_matches || !read_text(directory, "stat", text, 8192)) return false;
    const auto close = text.rfind(')');
    if (close == std::string::npos || close + 2 >= text.size()) return false;
    std::istringstream fields(text.substr(close + 2));
    std::string field;
    for (int number = 3; number <= 22; ++number) {
        if (!(fields >> field)) return false;
    }
    char* after = nullptr;
    errno = 0;
    const unsigned long long value = strtoull(field.c_str(), &after, 10);
    if (errno || field.empty() || field[0] == '-' || *after || value == 0) return false;
    start = value;
    return true;
}

struct Process {
    pid_t pid;
    uint64_t start;
};

std::vector<Process> find_processes(const char* package, uid_t uid) {
    std::vector<Process> result;
    DIR* directory = opendir(MAPLESU_PATH_PROC_ROOT);
    if (!directory) return result;
    while (const auto* entry = readdir(directory)) {
        if (!*entry->d_name || strspn(entry->d_name, "0123456789") != strlen(entry->d_name)) continue;
        char* after = nullptr;
        errno = 0;
        const long number = strtol(entry->d_name, &after, 10);
        if (errno || *after || number <= 0 || number > INT_MAX || number == getpid()) continue;
        Fd process(openat(dirfd(directory), entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        uint64_t start = 0;
        if (process.value >= 0 && process_identity(process.value, package, uid, start))
            result.push_back({static_cast<pid_t>(number), start});
    }
    closedir(directory);
    std::sort(result.begin(), result.end(), [](const Process& a, const Process& b) { return a.pid < b.pid; });
    return result;
}

struct Mapping {
    uintptr_t base = 0;
    unsigned device_major = 0, device_minor = 0;
    unsigned long long inode = 0;
    std::string path;
    std::vector<std::pair<uintptr_t, uintptr_t>> executable;
};

bool libc_mapping(int process, Mapping& result) {
    std::string text;
    if (!read_text(process, "maps", text, 4 * 1024 * 1024)) return false;
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        unsigned long long begin, end, offset, inode;
        unsigned device_major, device_minor;
        char permissions[5]{};
        int path_at = 0;
        if (sscanf(line.c_str(), "%llx-%llx %4s %llx %x:%x %llu %n", &begin, &end,
            permissions, &offset, &device_major, &device_minor, &inode, &path_at) != 7 ||
            !path_at || begin >= end || end > UINTPTR_MAX) continue;
        const std::string path = line.substr(static_cast<size_t>(path_at));
        if (path.size() < 8 || path.compare(path.size() - 8, 8, "/libc.so") != 0) continue;
        if (!result.base && offset == 0 && permissions[0] == 'r') {
            result.base = static_cast<uintptr_t>(begin);
            result.path = path;
            result.device_major = device_major;
            result.device_minor = device_minor;
            result.inode = inode;
        }
        if (result.path == path && result.inode == inode && result.device_major == device_major &&
            result.device_minor == device_minor && permissions[2] == 'x')
            result.executable.emplace_back(static_cast<uintptr_t>(begin), static_cast<uintptr_t>(end));
    }
    return result.base && !result.executable.empty();
}

constexpr const char* kSymbols[] = {"mmap", "munmap", "getenv", "setenv"};
using Functions = std::array<uintptr_t, 4>;

bool executable_addresses(const Mapping& target, const Functions& functions) {
    for (const auto address : functions) {
        bool executable = false;
        for (const auto& range : target.executable)
            if (address >= range.first && address < range.second) executable = true;
        if (!executable) return false;
    }
    return true;
}

bool memory_functions(const Mapping& target, Functions& functions) {
    Fd self(open("/proc/self", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    Mapping local;
    if (self.value < 0 || !libc_mapping(self.value, local) || local.path != target.path ||
        local.inode != target.inode || local.device_major != target.device_major ||
        local.device_minor != target.device_minor) return false;
    // NOLOAD prevents loading code from a target-controlled path into this worker.
    void* library = dlopen(local.path.c_str(), RTLD_NOW | RTLD_NOLOAD);
    if (!library) return false;
    bool found = true;
    for (size_t index = 0; index < functions.size(); ++index) {
        void* address = dlsym(library, kSymbols[index]);
        Dl_info info{};
        if (!address || !dladdr(address, &info) || reinterpret_cast<uintptr_t>(info.dli_fbase) != local.base ||
            reinterpret_cast<uintptr_t>(address) < local.base) { found = false; break; }
        const uintptr_t offset = reinterpret_cast<uintptr_t>(address) - local.base;
        if (offset > UINTPTR_MAX - target.base) { found = false; break; }
        functions[index] = target.base + offset;
    }
    dlclose(library);
    return found && executable_addresses(target, functions);
}

bool span(size_t total, uint64_t offset, uint64_t length) {
    return offset <= total && length <= total - offset;
}

int file_functions(const Mapping& target, Functions& functions) {
    Fd file(open(target.path.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    struct stat info{};
    if (file.value < 0) return kFileOpen;
    if (fstat(file.value, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr)) ||
        info.st_size > 64 * 1024 * 1024 || static_cast<unsigned long long>(info.st_ino) != target.inode ||
        major(info.st_dev) != target.device_major || minor(info.st_dev) != target.device_minor) return kFileInvalid;
    const size_t size = static_cast<size_t>(info.st_size);
    void* mapped = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, file.value, 0);
    if (mapped == MAP_FAILED) return kFileOpen;
    struct Unmap { void* data; size_t size; ~Unmap() { munmap(data, size); } } unmap{mapped, size};
    const auto* bytes = static_cast<const unsigned char*>(mapped);
    Elf64_Ehdr header{};
    memcpy(&header, bytes, sizeof(header));
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_machine != EM_AARCH64 || header.e_type != ET_DYN ||
        header.e_ehsize != sizeof(header) || header.e_shentsize != sizeof(Elf64_Shdr) || !header.e_shnum ||
        !span(size, header.e_shoff, uint64_t(header.e_shnum) * sizeof(Elf64_Shdr)) ||
        header.e_phentsize != sizeof(Elf64_Phdr) || !header.e_phnum ||
        !span(size, header.e_phoff, uint64_t(header.e_phnum) * sizeof(Elf64_Phdr))) return kFileInvalid;
    bool zero_bias = false;
    for (size_t index = 0; index < header.e_phnum; ++index) {
        Elf64_Phdr program{};
        memcpy(&program, bytes + header.e_phoff + index * sizeof(program), sizeof(program));
        if (program.p_type == PT_LOAD && program.p_offset == 0 && program.p_vaddr == 0) zero_bias = true;
    }
    if (!zero_bias) return kFileInvalid;
    functions.fill(0);
    for (size_t section = 0; section < header.e_shnum; ++section) {
        Elf64_Shdr table{};
        memcpy(&table, bytes + header.e_shoff + section * sizeof(table), sizeof(table));
        if (table.sh_type != SHT_DYNSYM) continue;
        if (table.sh_entsize != sizeof(Elf64_Sym) || table.sh_size % sizeof(Elf64_Sym) ||
            table.sh_link >= header.e_shnum || !span(size, table.sh_offset, table.sh_size)) return kFileInvalid;
        Elf64_Shdr strings{};
        memcpy(&strings, bytes + header.e_shoff + table.sh_link * sizeof(strings), sizeof(strings));
        if (strings.sh_type != SHT_STRTAB || !span(size, strings.sh_offset, strings.sh_size)) return kFileInvalid;
        for (uint64_t offset = 0; offset < table.sh_size; offset += sizeof(Elf64_Sym)) {
            Elf64_Sym symbol{};
            memcpy(&symbol, bytes + table.sh_offset + offset, sizeof(symbol));
            if (symbol.st_shndx == SHN_UNDEF || ELF64_ST_TYPE(symbol.st_info) != STT_FUNC ||
                ELF64_ST_BIND(symbol.st_info) != STB_GLOBAL || symbol.st_name >= strings.sh_size ||
                !symbol.st_value || symbol.st_value > UINTPTR_MAX - target.base) continue;
            const char* name = reinterpret_cast<const char*>(bytes + strings.sh_offset + symbol.st_name);
            if (!memchr(name, 0, static_cast<size_t>(strings.sh_size - symbol.st_name))) return kFileInvalid;
            for (size_t index = 0; index < functions.size(); ++index)
                if (strcmp(name, kSymbols[index]) == 0) functions[index] = target.base + symbol.st_value;
        }
    }
    return executable_addresses(target, functions) ? 0 : kFileInvalid;
}

// Linux AArch64 NT_PRSTATUS ABI; a compat (32-bit) register set is rejected.
struct Registers { uint64_t x[31], sp, pc, pstate; };
static_assert(sizeof(Registers) == 272);
constexpr uintptr_t kArmSystemCall = 0x404;

class Trace {
public:
    explicit Trace(pid_t pid) : pid_(pid) {}
    ~Trace() { finish(); }

    bool attach() {
        if (ptrace(PTRACE_SEIZE, pid_, nullptr, nullptr) != 0) return false;
        attached_ = true;
        if (ptrace(PTRACE_INTERRUPT, pid_, nullptr, nullptr) != 0 || !wait_stop()) return false;
        if (!registers(PTRACE_GETREGSET, NT_PRSTATUS, &saved_, sizeof(saved_)) ||
            !registers(PTRACE_GETREGSET, NT_FPREGSET, floating_.data(), floating_.size()) ||
            !registers(PTRACE_GETREGSET, kArmSystemCall, &syscall_, sizeof(syscall_))) return false;
        saved_valid_ = true;
        return true;
    }

    bool call(uintptr_t function, std::initializer_list<uint64_t> arguments, uint64_t& result) {
        if (!stopped_ || !saved_valid_ || arguments.size() > 8) return false;
        Registers current = saved_;
        size_t index = 0;
        for (const auto value : arguments) current.x[index++] = value;
        current.pc = function;
        current.x[30] = 0;
        // Cancel an interrupted syscall only while executing the injected call.
        int no_syscall = -1;
        if (!registers(PTRACE_SETREGSET, kArmSystemCall, &no_syscall, sizeof(no_syscall)) ||
            !registers(PTRACE_SETREGSET, NT_PRSTATUS, &current, sizeof(current)) ||
            ptrace(PTRACE_CONT, pid_, nullptr, nullptr) != 0) return false;
        stopped_ = false;
        if (!wait_stop() || !registers(PTRACE_GETREGSET, NT_PRSTATUS, &current, sizeof(current))) return false;
        // Returning through LR=0 is the sole synthetic completion signal.
        if (WSTOPSIG(status_) != SIGSEGV || current.pc != 0) {
            pending_signal_ = (static_cast<unsigned>(status_) >> 16) ? 0 : WSTOPSIG(status_);
            return false;
        }
        result = current.x[0];
        return true;
    }

    bool memory(uintptr_t address, void* data, size_t length, bool write) {
        if (!stopped_) return false;
        auto* bytes = static_cast<unsigned char*>(data);
        while (length) {
            const uintptr_t aligned = address & ~(sizeof(long) - 1);
            const size_t skip = address - aligned;
            const size_t count = std::min(length, sizeof(long) - skip);
            errno = 0;
            long word = ptrace(PTRACE_PEEKDATA, pid_, reinterpret_cast<void*>(aligned), nullptr);
            if (word == -1 && errno) return false;
            if (write) {
                memcpy(reinterpret_cast<unsigned char*>(&word) + skip, bytes, count);
                if (ptrace(PTRACE_POKEDATA, pid_, reinterpret_cast<void*>(aligned),
                    reinterpret_cast<void*>(static_cast<uintptr_t>(word))) != 0) return false;
            } else memcpy(bytes, reinterpret_cast<unsigned char*>(&word) + skip, count);
            bytes += count;
            length -= count;
            address += count;
        }
        return true;
    }

    bool string(uintptr_t address, std::string& text) {
        text.clear();
        for (size_t count = 0; count < kMaxPath; ++count) {
            char byte;
            if (!memory(address + count, &byte, 1, false)) return false;
            if (!byte) return true;
            text.push_back(byte);
        }
        return false;
    }

    bool finish() {
        if (!attached_) return finished_ok_;
        if (!stopped_) {
            if (ptrace(PTRACE_INTERRUPT, pid_, nullptr, nullptr) != 0 || !wait_stop()) return false;
        }
        bool restored = true;
        if (saved_valid_) {
            restored = registers(PTRACE_SETREGSET, NT_FPREGSET, floating_.data(), floating_.size());
            restored = registers(PTRACE_SETREGSET, NT_PRSTATUS, &saved_, sizeof(saved_)) && restored;
            restored = registers(PTRACE_SETREGSET, kArmSystemCall, &syscall_, sizeof(syscall_)) && restored;
        }
        const bool detached = ptrace(PTRACE_DETACH, pid_, nullptr,
            reinterpret_cast<void*>(static_cast<uintptr_t>(pending_signal_))) == 0;
        if (detached) attached_ = false;
        finished_ok_ = restored && detached;
        return finished_ok_;
    }

private:
    bool registers(decltype(PTRACE_GETREGSET) request, uintptr_t kind, void* data, size_t size) {
        iovec vector{data, size};
        return ptrace(request, pid_, reinterpret_cast<void*>(kind), &vector) == 0 && vector.iov_len == size;
    }

    bool wait_stop() {
        const auto deadline = Clock::now() + std::chrono::seconds(2);
        while (Clock::now() < deadline) {
            const pid_t waited = waitpid(pid_, &status_, __WALL | WNOHANG);
            if (waited == pid_) { stopped_ = WIFSTOPPED(status_); return stopped_; }
            if (waited < 0 && errno != EINTR) return false;
            usleep(1000);
        }
        return false;
    }

    pid_t pid_;
    int status_ = 0, pending_signal_ = 0, syscall_ = -1;
    bool attached_ = false, stopped_ = false, saved_valid_ = false, finished_ok_ = true;
    Registers saved_{};
    std::array<unsigned char, 528> floating_{};
};

struct Attempt { int code; bool retryable; };

Attempt inject_one(const Process& process, const char* package, uid_t uid, const char* directory, int mode) {
    const std::string path = std::string(MAPLESU_PATH_PROC_ROOT) + "/" + std::to_string(process.pid);
    Fd proc(open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    const auto same = [&] {
        uint64_t start = 0;
        return proc.value >= 0 && process_identity(proc.value, package, uid, start) && start == process.start;
    };
    if (!same()) return {kRejected, false};
    Mapping mapping;
    if (!libc_mapping(proc.value, mapping)) return {kLibcMissing, true};
    Functions functions{};
    int resolved = 0;
    if (mode == 0 || !memory_functions(mapping, functions))
        resolved = mode == 1 ? kLibcMissing : file_functions(mapping, functions);
    if (resolved) return {resolved, true};
    if (!same()) return {kRejected, false};
    Trace trace(process.pid);
    if (!trace.attach() || !same()) return {kRejected, false};

    uint64_t scratch = 0;
    bool setenv_started = false;
    const auto apply = [&]() -> bool {
        if (!same() || !trace.call(functions[0], {0, kMaxPath + PATH_MAX, PROT_READ | PROT_WRITE,
                MAP_PRIVATE | MAP_ANONYMOUS, UINT64_MAX, 0}, scratch) ||
            !scratch || scratch >= UINTPTR_MAX - (kMaxPath + PATH_MAX)) return false;
        char name[] = "PATH";
        if (!trace.memory(scratch, name, sizeof(name), true)) return false;
        uint64_t previous = 0;
        if (!trace.call(functions[2], {scratch}, previous)) return false;
        std::string old;
        if (previous && !trace.string(previous, old)) return false;
        const std::string prefix = std::string(directory) + ":";
        if (old == directory || old.compare(0, prefix.size(), prefix) == 0) return same();
        const std::string updated = prefix + old;
        if (updated.size() + sizeof(name) + 1 > kMaxPath + PATH_MAX || !same() ||
            !trace.memory(scratch + sizeof(name), const_cast<char*>(updated.c_str()), updated.size() + 1, true))
            return false;
        uint64_t result = UINT64_MAX;
        setenv_started = true;
        if (!trace.call(functions[3], {scratch, scratch + sizeof(name), 1}, result) || result != 0 || !same()) return false;
        uint64_t actual = 0;
        std::string value;
        return trace.call(functions[2], {scratch}, actual) && actual && trace.string(actual, value) &&
            value == updated && same();
    };
    const bool applied = apply();
    bool released = true;
    if (scratch && scratch < UINTPTR_MAX - (kMaxPath + PATH_MAX)) {
        uint64_t result = UINT64_MAX;
        released = trace.call(functions[1], {scratch, kMaxPath + PATH_MAX}, result) && result == 0;
    }
    const bool finished = trace.finish();
    return {applied && released && finished ? 0 : kRejected, !setenv_started && finished};
}

[[maybe_unused]] int inject_path(const char* package, uid_t uid, const char* directory, int wait_ms) {
    // This is an explicit, admitted Owner action. Ordinary app startup never calls it.
    const auto deadline = Clock::now() + std::chrono::milliseconds(std::max(wait_ms, 0));
    std::vector<Process> processes;
    do {
        processes = find_processes(package, uid);
        if (!processes.empty() || Clock::now() >= deadline) break;
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count();
        if (left > 0) usleep(static_cast<useconds_t>(std::min<int64_t>(left, 100) * 1000));
    } while (true);
    if (processes.empty()) {
        fprintf(stderr, "maplesu-runtime: no process for %s\n", package);
        return 68;
    }
    constexpr const char* modes[] = {"libc-file", "self-mem", "read-all"};
    int counts[3]{}, last = kRejected;
    for (const auto& process : processes) {
        bool done = false, retryable = true;
        for (int round = 0; round < 2 && retryable && !done; ++round) {
            if (round) usleep(60000);
            for (int mode = 2; mode >= 0 && retryable; --mode) {
                const auto result = inject_one(process, package, uid, directory, mode);
                last = result.code;
                retryable = result.retryable;
                if (!result.code) {
                    ++counts[mode];
                    printf("TARGET:%d:%s\n", process.pid, modes[mode]);
                    done = true;
                    break;
                }
            }
        }
        if (!done) fprintf(stderr, "maplesu-runtime: pid %d rejected code=%d\n", process.pid, last);
    }
    const int total = counts[0] + counts[1] + counts[2];
    if (!total) {
        fprintf(stderr, "maplesu-runtime: injection rejected code=%d\n", last);
        return 69;
    }
    printf("INJECTED:%d\nMODES:%d:%d:%d\n", total, counts[2], counts[1], counts[0]);
    return 0;
}
}

int maplesu_inject_path(const char* package, uid_t expected_full_uid, const char* directory, int wait_ms) {
    if (!package_name(package) || expected_full_uid < 10000 || !directory || directory[0] != '/' ||
        strnlen(directory, PATH_MAX) == PATH_MAX || strchr(directory, ':') || strchr(directory, '\n')) return 64;
    if (getuid() != 0 || geteuid() != 0) return 69;
#if defined(__ANDROID__) && defined(__aarch64__)
    return inject_path(package, expected_full_uid, directory, wait_ms);
#else
    (void)wait_ms;
    fprintf(stderr, "maplesu-runtime: PATH injection requires Android AArch64\n");
    return 69;
#endif
}
