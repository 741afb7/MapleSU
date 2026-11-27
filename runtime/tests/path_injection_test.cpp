// Host fixture executes the production parser, resolver, transaction and cleanup.
// Ptrace and waits model the AArch64 kernel ABI; this is not device evidence.
#include "../src/path_injection.cpp"
#include <cassert>
#include <cstdarg>
#include <filesystem>
#include <fstream>
#include <iostream>

namespace fs = std::filesystem;
namespace fixture {
const fs::path root = "/tmp/maplesu-path-test";
constexpr uintptr_t scratch = 0x900000, environment = 0x800000;
Registers initial{}, current{};
Functions functions{};
std::array<unsigned char, 528> fp{}, initial_fp{};
std::vector<unsigned char> memory(65536, 0xaa);
std::string value = "/system/bin";
int syscall_number = 98, initial_syscall = 98, next_stop = 0;
int attachments = 0, detachments = 0, allocations = 0, releases = 0, writes = 0, delivered_signal = 0;
uid_t caller = 0;
bool compat = false, attach_rejected = false, restore_rejected = false;
bool allocation_rejected = false, setenv_rejected = false, release_rejected = false, identity_changed = false;
bool no_environment = false, mutation_fault = false;

void put(const fs::path& path, const std::string& value) {
    std::ofstream output(path, std::ios::binary);
    output.write(value.data(), value.size()); assert(output.good());
}
void identity(int pid, uid_t uid, uint64_t start = 1234, const std::string& command = "dev.fixture.host") {
    const auto directory = root / "proc" / std::to_string(pid);
    fs::create_directories(directory);
    put(directory / "cmdline", command + '\0' + "ignored");
    put(directory / "status", "Name:\tfixture\nUid:\t" + std::to_string(uid) + "\t" + std::to_string(uid) +
        "\t" + std::to_string(uid) + "\t" + std::to_string(uid) + "\n");
    std::string stat = std::to_string(pid) + " (fixture ) name) S";
    for (int number = 4; number < 22; ++number) stat += " 0";
    put(directory / "stat", stat + " " + std::to_string(start) + " 0\n");
}
void reset() {
    initial = {}; initial.pc = 0x77770; initial.sp = 0x700000;
    for (size_t index = 0; index < 31; ++index) initial.x[index] = index + 100;
    current = initial;
    initial_fp.fill(0x42); fp = initial_fp; memory.assign(memory.size(), 0xaa);
    value = "/system/bin"; syscall_number = initial_syscall; next_stop = 0;
    attachments = detachments = allocations = releases = writes = delivered_signal = 0;
    caller = 0;
    compat = attach_rejected = restore_rejected = allocation_rejected = setenv_rejected = false;
    release_rejected = identity_changed = no_environment = mutation_fault = false;
    identity(101, 10489);
}
unsigned char* bytes(uintptr_t address, size_t length) {
    if (address < scratch || address - scratch > memory.size() || length > memory.size() - (address - scratch))
        return nullptr;
    return memory.data() + address - scratch;
}
std::string remote_string(uintptr_t address) {
    auto* begin = bytes(address, 1); assert(begin);
    return reinterpret_cast<char*>(begin);
}
void expect(bool result, const char* name) {
    if (!result) { std::cerr << "FAIL " << name << '\n'; abort(); }
    std::cout << "PASS " << name << '\n';
}
bool restored() {
    return detachments == 1 && memcmp(&current, &initial, sizeof(current)) == 0 &&
        fp == initial_fp && syscall_number == initial_syscall;
}
}

extern "C" uid_t __wrap_getuid() { return fixture::caller; }
extern "C" uid_t __wrap_geteuid() { return fixture::caller; }
extern "C" pid_t __wrap_waitpid(pid_t pid, int* status, int) {
    assert(fixture::next_stop);
    *status = fixture::next_stop; fixture::next_stop = 0; return pid;
}
extern "C" long __wrap_ptrace(int request, ...) {
    using namespace fixture;
    va_list arguments; va_start(arguments, request);
    const pid_t pid = va_arg(arguments, pid_t);
    void* address = va_arg(arguments, void*);
    void* data = va_arg(arguments, void*);
    va_end(arguments);
    assert(pid == 101);
    if (request == PTRACE_SEIZE) {
        ++attachments;
        if (attach_rejected) { errno = EPERM; return -1; }
        if (identity_changed) identity(101, 110489, 4321);
        return 0;
    }
    if (request == PTRACE_INTERRUPT) { next_stop = (128 << 16) | (SIGTRAP << 8) | 0x7f; return 0; }
    if (request == PTRACE_DETACH) { ++detachments; delivered_signal = reinterpret_cast<uintptr_t>(data); return 0; }
    if (request == PTRACE_GETREGSET || request == PTRACE_SETREGSET) {
        auto& vector = *static_cast<iovec*>(data);
        const auto kind = reinterpret_cast<uintptr_t>(address);
        void* state = kind == NT_PRSTATUS ? static_cast<void*>(&current) :
            kind == NT_FPREGSET ? static_cast<void*>(fp.data()) : static_cast<void*>(&syscall_number);
        const size_t size = kind == NT_PRSTATUS ? sizeof(current) : kind == NT_FPREGSET ? fp.size() : sizeof(syscall_number);
        assert(vector.iov_len == size);
        if (request == PTRACE_GETREGSET) {
            memcpy(vector.iov_base, state, size);
            if (compat && kind == NT_PRSTATUS) vector.iov_len = 72;
        } else {
            if (restore_rejected && kind == NT_PRSTATUS && static_cast<Registers*>(vector.iov_base)->pc == initial.pc)
                return -1;
            memcpy(state, vector.iov_base, size);
        }
        return 0;
    }
    if (request == PTRACE_PEEKDATA || request == PTRACE_POKEDATA) {
        const auto location = reinterpret_cast<uintptr_t>(address);
        if (request == PTRACE_PEEKDATA && location >= environment && location < environment + value.size() + 16) {
            long word = 0;
            const size_t offset = location - environment;
            if (offset <= value.size()) memcpy(&word, value.c_str() + offset, std::min(sizeof(word), value.size() + 1 - offset));
            return word;
        }
        auto* at = bytes(location, sizeof(long));
        if (!at) { errno = EFAULT; return -1; }
        long word;
        if (request == PTRACE_PEEKDATA) { memcpy(&word, at, sizeof(word)); return word; }
        word = reinterpret_cast<uintptr_t>(data); memcpy(at, &word, sizeof(word)); return 0;
    }
    if (request == PTRACE_CONT) {
        const auto function = current.pc;
        fp.fill(0x75);
        if (function == functions[0]) { ++allocations; current.x[0] = allocation_rejected ? UINT64_MAX : scratch; }
        else if (function == functions[1]) { ++releases; current.x[0] = release_rejected ? UINT64_MAX : 0; }
        else if (function == functions[2]) { assert(remote_string(current.x[0]) == "PATH"); current.x[0] = no_environment ? 0 : environment; }
        else if (function == functions[3]) {
            ++writes; assert(remote_string(current.x[0]) == "PATH" && current.x[2] == 1);
            if (!setenv_rejected) { value = remote_string(current.x[1]); no_environment = false; }
            current.x[0] = setenv_rejected ? UINT64_MAX : 0;
            if (mutation_fault) { current.pc = 0xdead0; next_stop = (SIGILL << 8) | 0x7f; return 0; }
        } else { std::cerr << "unexpected function " << std::hex << function << '\n'; abort(); }
        current.pc = 0; next_stop = (SIGSEGV << 8) | 0x7f; return 0;
    }
    abort();
}

int main(int argc, char** argv) {
    using namespace fixture;
    assert(argc == 2 && !fs::exists(root));
    fs::create_directories(root);
    fs::copy_file(argv[1], root / "libc.so");
    reset();
    identity(102, 110489, 1235, "dev.fixture.host:remote");
    identity(103, 10489, 1236, "dev.fixture.hostile");
    identity(104, 10489, 1237, "dev.fixture.host:worker");
    auto found = find_processes("dev.fixture.host", 10489);
    expect(found.size() == 2 && found[0].pid == 101 && found[1].pid == 104,
        "fullUID package and package:process matching excludes another user and prefixes");
    put(root / "proc/104/status", "Uid: 10489 10489 10489 110489\n");
    expect(find_processes("dev.fixture.host", 10489).size() == 1, "filesystem UID mismatch rejected");
    struct stat info{}; assert(stat((root / "libc.so").c_str(), &info) == 0);
    char maps[2048];
    snprintf(maps, sizeof(maps), "100000-101000 r--p 00000000 %x:%x %llu %s\n101000-200000 r-xp 00001000 %x:%x %llu %s\n",
        major(info.st_dev), minor(info.st_dev), static_cast<unsigned long long>(info.st_ino), (root / "libc.so").c_str(),
        major(info.st_dev), minor(info.st_dev), static_cast<unsigned long long>(info.st_ino), (root / "libc.so").c_str());
    put(root / "proc/101/maps", maps);
    Fd process(open((root / "proc/101").c_str(), O_DIRECTORY | O_RDONLY));
    Mapping mapping;
    expect(libc_mapping(process.value, mapping) && file_functions(mapping, functions) == 0,
        "current AArch64 ELF dynsym resolves four executable libc functions");
    Mapping wrong = mapping; ++wrong.inode;
    expect(file_functions(wrong, functions) == kFileInvalid, "replaced libc inode rejected");
    assert(file_functions(mapping, functions) == 0);
    const Process target{101, 1234};
    auto attempt = [&](int mode = 2) { return inject_one(target, "dev.fixture.host", 10489, "/private/su", mode); };

    reset();
    expect(attempt(1).code == kLibcMissing && attachments == 0, "self-memory mode never falls back to file");
    reset(); auto result = attempt();
    expect(result.code == 0 && value == "/private/su:/system/bin" && writes == 1 &&
        allocations == 1 && releases == 1 && restored() && delivered_signal == 0,
        "read-all file fallback injects PATH and restores all saved state");
    reset(); result = attempt(0);
    expect(result.code == 0 && restored(), "explicit file strategy retains successful transaction");
    reset(); value = "/private/su:/system/bin"; result = attempt();
    expect(result.code == 0 && writes == 0 && releases == 1 && restored(), "existing PATH prefix is idempotent");
    reset(); no_environment = true; result = attempt();
    expect(result.code == 0 && value == "/private/su:" && restored(), "missing PATH retains existing prepend semantics");
    reset(); identity(101, 10489, 4321); result = attempt();
    expect(result.code != 0 && attachments == 0, "reused PID starttime rejected before attach");
    reset(); identity_changed = true; result = attempt();
    expect(result.code != 0 && writes == 0 && allocations == 0 && detachments == 1,
        "fullUID and starttime change during attach rejects mutation");
    reset(); compat = true; result = attempt();
    expect(result.code != 0 && writes == 0 && detachments == 1, "32-bit register ABI rejected without mutation");
    reset(); attach_rejected = true; result = attempt();
    expect(result.code != 0 && allocations == 0, "real ptrace admission error retained");
    reset(); allocation_rejected = true; result = attempt();
    expect(result.code != 0 && writes == 0 && releases == 0 && restored(), "mmap failure restores target without invalid unmap");
    reset(); setenv_rejected = true; result = attempt();
    expect(result.code != 0 && !result.retryable && value == "/system/bin" && releases == 1 && restored(),
        "setenv failure is reported once with scratch cleanup");
    reset(); mutation_fault = true; result = attempt();
    expect(result.code != 0 && !result.retryable && releases == 1 && restored() && delivered_signal == SIGILL,
        "unexpected remote fault remains failure with real signal and no replay");
    reset(); release_rejected = true; result = attempt();
    expect(result.code != 0 && !result.retryable && restored(), "munmap failure is not published as success");
    reset(); restore_rejected = true; result = attempt();
    expect(result.code != 0 && !result.retryable && detachments == 1, "register restoration failure is not published as success");
    reset();
    { Trace trace(101); assert(trace.attach()); char bytes[] = {'X', 'Y'};
      expect(trace.memory(scratch + 3, bytes, sizeof(bytes), true) && memory[2] == 0xaa && memory[3] == 'X' &&
        memory[4] == 'Y' && memory[5] == 0xaa, "partial ptrace write preserves adjacent target bytes");
      assert(trace.finish()); }
    reset(); caller = 10489;
    expect(maplesu_inject_path("dev.fixture.host", 10489, "/private/su", 0) == 69 && attachments == 0,
        "ordinary caller rejected without process attachment");
    reset();
    expect(maplesu_inject_path("dev.fixture.host", 489, "/private/su", 0) == 64 &&
        maplesu_inject_path("dev.fixture;host", 10489, "/private/su", 0) == 64 &&
        maplesu_inject_path("dev.fixture.host", 10489, "/private/su:/system/bin", 0) == 64 && attachments == 0,
        "appId package metacharacters and invalid PATH directory rejected");
    reset();
    expect(inject_path("dev.fixture.absent", 10489, "/private/su", 0) == 68 && attachments == 0,
        "missing process returns actual no-target status");
    reset();
    expect(inject_path("dev.fixture.host", 10489, "/private/su", 0) == 0 && writes == 1 && restored(),
        "current orchestrator emits TARGET INJECTED and MODES from real transaction result");
    fs::remove_all(root);
}
