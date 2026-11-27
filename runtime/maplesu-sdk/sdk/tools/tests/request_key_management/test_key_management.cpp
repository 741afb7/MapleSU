#include "module_base_kernel_request.h"
#include "maplesu_request_private.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>
#include <sys/wait.h>

namespace kernel_module { extern "C" int64_t maplesu_release_staging(uint64_t); }

namespace {
using namespace kernel_module;
constexpr uint64_t address = UINT64_C(0xffff000012340000);
constexpr uint64_t handle = UINT64_C(0xa1b2c3d400000005);
constexpr uint64_t session = UINT64_C(0xe1f2a3b400000007);
constexpr uint64_t answer = UINT64_C(0xfedcba9876543210);
constexpr uint64_t untouched = UINT64_C(0x1948573665748392);
constexpr maplesu_host_instance host{UINT64_C(0x123456789abcdef0), UINT64_C(0xfedcba9876543211)};
enum class Completion { Good, Missing, Magic, Version, Length, Command, Request, Positive, Unsupported };
struct Fixture {
    std::string key = std::string(48, 'K');
    pid_t process = 901;
    uid_t uid = 1000;
    std::array<unsigned, 64> calls{};
    std::vector<uint8_t> memory;
    uint32_t fail_command = 0;
    int32_t status = 0;
    uint32_t effects = 0;
    uint64_t failed_result = 0;
    uint64_t failed_handle = 0;
    uint64_t return_value = answer;
    uint64_t active_session = session - 1, active_handle = handle - 1;
    Completion completion = Completion::Good;
    bool allocated = false, old_v2 = false, old_no_v2 = false, nonzero_unsupported = false;
    bool mutate_key = false, mutate_process = false, fail_new = false, allow_legacy = false;
    unsigned prepares = 0, saved_reads = 0, legacy = 0, execs = 0;
    unsigned legacy_allocs = 0, legacy_writes = 0;
    uint32_t saved_uid = 1000, saved_gid = 1000;
};
thread_local Fixture state;
std::atomic<unsigned> checks{0}, failures{0};
std::mutex id_mutex;
std::unordered_set<uint64_t> ids;

void check(bool value, const char* label) {
    ++checks;
    if (!value) { ++failures; std::fprintf(stderr, "FAIL: %s\n", label); }
}
bool same_host(const maplesu_host_instance& value) { return value.low == host.low && value.high == host.high; }
unsigned commands() { unsigned total = 0; for (auto n : state.calls) total += n; return total; }
KModErr allocate(uint32_t size, uint64_t& output, bool* was_supported = nullptr) {
    bool supported = false;
    const auto error = maplesu_request_key_alloc(size, output, supported);
    if (was_supported) *was_supported = supported;
    return error;
}
void open_allocation(uint32_t size = 333) {
    uint64_t output = untouched;
    bool supported = false;
    check(allocate(size, output, &supported) == KModErr::OK && supported && output == address,
          "real Key client opens and returns the full allocation address");
    check(maplesu_request_managed_allocation(address), "allocation is tracked by its real client owner");
}
void close_session() {
    check(maplesu_request_owner_close() == KModErr::OK, "explicit Key session close completes");
    check(maplesu_request_management_reply().effects & MAPLESU_EFFECT_SESSION_CLOSED,
          "close retains its explicit session completion");
}
void no_credentials() {
    check(!state.legacy && state.saved_reads == 2 * state.prepares &&
          state.prepares <= state.calls[MAPLESU_REQUEST_QUERY] + 1,
          "only the compatibility QUERY probe reads saved IDs; management never uses legacy mutation");
}
void roundtrip() {
    open_allocation();
    const auto prepares = state.prepares, saved_reads = state.saved_reads;
    std::array<uint8_t, 333> bytes{};
    for (size_t i = 0; i < bytes.size(); ++i) bytes[i] = static_cast<uint8_t>(i * 29 + 7);
    check(unsafe_request_kernel_write_mem(address, bytes.data(), bytes.size()) == KModErr::OK,
          "public unsafe WRITE reaches the independent management provider");
    check(state.calls[MAPLESU_REQUEST_WRITE] == 1 && state.memory == std::vector<uint8_t>(bytes.begin(), bytes.end()),
          "WRITE transfers a complete bounded buffer once");
    std::array<uint8_t, 333> read{};
    check(maplesu_request_v2_read(address, read.data(), read.size()) == KModErr::OK && read == bytes,
          "production v2 READ returns the bytes written through management");
    uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, bytes.size(), output) == KModErr::OK && output == answer,
          "public EXEC publishes every return bit");
    auto receipt = maplesu_request_management_reply();
    check(receipt.completed && receipt.command == MAPLESU_REQUEST_EXEC && !receipt.status &&
          receipt.result == answer && receipt.effects == (MAPLESU_EFFECT_EXECUTED | MAPLESU_EFFECT_RELEASED),
          "complete EXEC receipt keeps status, effects and independent u64 result");
    check(state.prepares == prepares && state.saved_reads == saved_reads,
          "WRITE/READ/EXEC do no credential preparation or saved-ID receipt reads");
    check(maplesu_request_managed_allocation(address), "completed EXEC retains provenance against protocol downgrade");
    const auto before = commands();
    output = untouched;
    check(unsafe_request_kernel_exec_mem(address, bytes.size(), output) != KModErr::OK && output == untouched,
          "a consumed allocation never executes twice");
    check(commands() == before && state.execs == 1, "duplicate EXEC issues no second command");
    close_session(); no_credentials();
}
void execute() {
    const uint8_t code[] = {0x21, 0x43, 0x65, 0x87, 0x09, 0xab, 0xcd, 0xef};
    uint64_t output = untouched; bool supported = false;
    check(maplesu_request_key_execute(code, sizeof(code), output, supported) == KModErr::OK && supported && output == answer,
          "typed Key execute owns the complete allocation/write/execute chain");
    check(state.calls[MAPLESU_REQUEST_SESSION_OPEN] == 1 && state.calls[MAPLESU_REQUEST_ALLOC] == 1 &&
          state.calls[MAPLESU_REQUEST_WRITE] == 1 && state.calls[MAPLESU_REQUEST_EXEC] == 1 && !state.allocated,
          "typed execution performs each mutation once and releases");
    close_session(); no_credentials();
}
void release() {
    open_allocation();
    check(maplesu_release_staging(address) == 0 && !state.allocated,
          "staging release reaches the real Key management lifetime");
    const auto before = commands();
    check(maplesu_release_staging(address) != 0 && commands() == before, "duplicate release is rejected locally");
    close_session(); no_credentials();
}
void bounds() {
    uint64_t output = untouched;
    check(allocate(0, output) != KModErr::OK && output == untouched && !commands(), "zero size is local");
    check(allocate(MAPLESU_MANAGEMENT_MAX_BYTES + 1, output) != KModErr::OK && !commands(), "oversize allocation is local");
    open_allocation(16);
    const uint8_t bytes[17]{};
    const auto before = commands();
    check(unsafe_request_kernel_write_mem(address, nullptr, 1) != KModErr::OK, "null WRITE fails");
    check(unsafe_request_kernel_write_mem(address + 15, bytes, 2) != KModErr::OK, "WRITE crossing owner length fails");
    check(unsafe_request_kernel_exec_mem(address, 15, output) != KModErr::OK && output == untouched,
          "EXEC with a different extent preserves output");
    check(commands() == before, "invalid tracked operations do not reach provider");
    close_session(); no_credentials();
}
void open_unknown() {
    state.fail_command = MAPLESU_REQUEST_SESSION_OPEN; state.completion = Completion::Missing;
    uint64_t output = untouched; bool supported = false;
    check(allocate(8, output, &supported) != KModErr::OK && supported && output == untouched,
          "missing OPEN completion preserves output and forbids compatibility fallback");
    const auto before = commands();
    check(allocate(8, output, &supported) != KModErr::OK && supported && commands() == before,
          "unknown OPEN is never automatically replayed");
    state.fail_command = 0; state.completion = Completion::Good;
    close_session(); open_allocation(8); close_session(); no_credentials();
}
void exec_unknown() {
    open_allocation(8);
    state.fail_command = MAPLESU_REQUEST_EXEC; state.completion = Completion::Missing;
    uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched,
          "missing EXEC completion never publishes an assumed return value");
    const auto before = commands();
    check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && commands() == before,
          "unknown EXEC remains blocked without replay");
    check(state.execs == 1, "unknown EXEC may have executed exactly once");
    state.fail_command = 0; state.completion = Completion::Good;
    close_session(); no_credentials();
}
void negative() {
    for (const int32_t native : {-ENOMEM, -EFAULT, -EINVAL, -EIO}) {
        state.fail_command = MAPLESU_REQUEST_ALLOC; state.status = native;
        uint64_t output = untouched; errno = 0;
        check(allocate(8, output) != KModErr::OK && output == untouched && errno == -native,
              "completed allocation error preserves the native errno and output");
        const auto receipt = maplesu_request_management_reply();
        check(receipt.completed && receipt.status == native && !receipt.result && !receipt.effects,
              "completed negative allocation receipt is retained");
    }
    state.fail_command = 0; state.status = 0;
    close_session(); no_credentials();
}
void exec_effect_error() {
    open_allocation(8);
    state.fail_command = MAPLESU_REQUEST_EXEC; state.status = -EIO;
    state.effects = MAPLESU_EFFECT_EXECUTED | MAPLESU_EFFECT_RELEASED; state.failed_result = answer;
    uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched && errno == EIO,
          "post-execution error preserves the caller output and real errno");
    const auto receipt = maplesu_request_management_reply();
    check(receipt.completed && receipt.status == -EIO && receipt.result == answer && receipt.effects == state.effects,
          "post-execution error retains the complete executed-and-released fact");
    check(!state.allocated, "released error result consumes the producer resource");
    state.fail_command = 0; state.status = 0; close_session(); no_credentials();
}
void release_error() {
    open_allocation(8);
    state.fail_command = MAPLESU_REQUEST_RELEASE; state.status = -EIO; state.effects = MAPLESU_EFFECT_QUARANTINED;
    check(maplesu_release_staging(address) != 0 && errno == EIO && !state.allocated,
          "quarantined release error keeps errno and consumes local ownership");
    const auto receipt = maplesu_request_management_reply();
    check(receipt.completed && receipt.status == -EIO && receipt.effects == MAPLESU_EFFECT_QUARANTINED,
          "release failure receipt is not erased");
    state.fail_command = 0; state.status = 0; close_session(); no_credentials();
}
void close_error() {
    open_allocation(8);
    state.fail_command = MAPLESU_REQUEST_SESSION_CLOSE; state.status = -EIO;
    state.effects = MAPLESU_EFFECT_SESSION_CLOSED | MAPLESU_EFFECT_QUARANTINED;
    check(maplesu_request_owner_close() != KModErr::OK && errno == EIO && !state.allocated,
          "close keeps cleanup failure while invalidating the closed generation");
    const auto receipt = maplesu_request_management_reply();
    check(receipt.completed && receipt.status == -EIO && receipt.effects == state.effects,
          "close error retains its closed/quarantined completion");
    state.fail_command = 0; state.status = 0;
    open_allocation(8); close_session(); no_credentials();
}
void key_change() {
    open_allocation(8);
    state.fail_command = MAPLESU_REQUEST_EXEC; state.mutate_key = true;
    uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched,
          "identity change after EXEC prevents publishing success output");
    const auto receipt = maplesu_request_management_reply();
    check(receipt.completed && receipt.result == answer && (receipt.effects & MAPLESU_EFFECT_EXECUTED),
          "identity loss does not erase an independently completed mutation");
    check(state.execs == 1, "identity loss never retries execution");
    no_credentials();
}
void fork_identity() {
    open_allocation(8); ++state.process;
    uint64_t output = untouched; const auto before = commands();
    check(maplesu_request_managed_allocation(address), "fork retains provenance against protocol downgrade");
    check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched && commands() == before,
          "fork cannot execute a parent's allocation");
    open_allocation(8);
    check(state.calls[MAPLESU_REQUEST_SESSION_OPEN] == 2, "fork obtains a distinct management session");
    close_session(); no_credentials();
}
void old_v2(bool malformed) {
    state.old_v2 = true; state.nonzero_unsupported = malformed;
    uint64_t output = untouched; bool supported = false;
    const auto error = allocate(8, output, &supported);
    check((malformed ? error != KModErr::OK && supported : error == KModErr::OK && !supported) && output == untouched,
          "only a complete zero-output UNSUPPORTED OPEN allows the central compatibility path");
    check(state.calls[MAPLESU_REQUEST_SESSION_OPEN] == 1 && !state.legacy,
          "the typed adapter never dispatches its own legacy mutation");
}
void bad_completion() {
    for (const auto mode : {Completion::Magic, Completion::Version, Completion::Length,
                           Completion::Command, Completion::Request, Completion::Positive}) {
        std::thread worker([mode] {
        check(maplesu_request_select_legacy(state.key.c_str()) == KModErr::OK, "worker explicitly selects its historical credential");
            state.fail_command = MAPLESU_REQUEST_SESSION_OPEN; state.completion = mode;
            uint64_t output = untouched; bool supported = false;
            check(allocate(8, output, &supported) != KModErr::OK && supported && output == untouched,
                  "malformed completion is a closed management error, never compatibility");
            const auto before = commands();
            check(allocate(8, output, &supported) != KModErr::OK && commands() == before,
                  "malformed OPEN is not retried");
            no_credentials();
        });
        worker.join();
    }
}
void threads() {
    std::vector<std::thread> workers;
    for (int i = 0; i < 8; ++i) workers.emplace_back([i] {
        check(maplesu_request_select_legacy(state.key.c_str()) == KModErr::OK, "worker explicitly selects its historical credential");
        state.key[0] = static_cast<char>('A' + i);
        for (int n = 0; n < 20; ++n) {
            open_allocation(8);
            uint64_t output = untouched;
            check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
                  "concurrent worker executes only its tracked resource");
        }
        check(state.calls[MAPLESU_REQUEST_SESSION_OPEN] == 1 && state.execs == 20,
              "each live worker owns one session and exact execution count");
        close_session(); no_credentials();
    });
    for (auto& worker : workers) worker.join();
}
void allocation_failure() {
    state.fail_new = true;
    uint64_t output = untouched; bool supported = false;
    check(allocate(8, output, &supported) == KModErr::ERR_NO_MEM && output == untouched && !commands(),
          "packet allocation failure is reported before transport and preserves output");
    no_credentials();
}
void execution_failure(uint32_t command, bool unknown) {
    state.fail_command = command; state.status = unknown ? 0 : -EFAULT;
    state.completion = unknown ? Completion::Missing : Completion::Good;
    const uint8_t code[8]{};
    uint64_t output = untouched; bool supported = false;
    check(maplesu_request_key_execute(code, sizeof(code), output, supported) != KModErr::OK &&
          supported && output == untouched, "failed compound execution preserves the caller result");
    const auto receipt = maplesu_request_management_reply();
    if (unknown) {
        check(!receipt.completed && !state.calls[MAPLESU_REQUEST_RELEASE],
              "unknown WRITE does not release possibly in-use storage or invent completion");
        const auto before = commands();
        check(maplesu_request_key_execute(code, sizeof(code), output, supported) != KModErr::OK &&
              commands() == before, "unknown compound execution is not replayed");
    } else {
        check(receipt.completed && receipt.command == command && receipt.status == -EFAULT &&
              errno == EFAULT && !receipt.effects, "cleanup preserves the command's original negative receipt");
        check(state.calls[MAPLESU_REQUEST_RELEASE] == 1 && !state.allocated,
              "completed pre-execution failure releases its owned resource exactly once");
    }
    state.fail_command = 0; state.status = 0; state.completion = Completion::Good;
    close_session(); no_credentials();
}
void close_unknown() {
    open_allocation(8);
    state.fail_command = MAPLESU_REQUEST_SESSION_CLOSE; state.completion = Completion::Missing;
    check(maplesu_request_owner_close() != KModErr::OK, "missing CLOSE completion stays unknown");
    uint64_t output = untouched; const auto before = commands();
    check(allocate(8, output) != KModErr::OK && output == untouched && commands() == before,
          "unknown CLOSE does not reopen a session or publish an allocation");
    state.fail_command = 0; state.completion = Completion::Good;
    close_session(); open_allocation(8); close_session(); no_credentials();
}
void closed_address() {
    open_allocation(8); close_session();
    const auto before = commands(); uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched,
          "closed generation cannot execute a former allocation");
    check(maplesu_release_staging(address) != 0 && commands() == before,
          "closed provenance never falls back to legacy execution or release");
    open_allocation(8);
    check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
          "same address can be reused only through a fresh live allocation");
    close_session(); no_credentials();
}
void return_bits() {
    for (const uint64_t value : {UINT64_C(0), UINT64_C(0xffffffff), UINT64_C(0x100000000),
                                UINT64_C(0x8000000000000000), UINT64_MAX}) {
        open_allocation(8); state.return_value = value; uint64_t output = untouched;
        check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == value,
              "zero, signed high bit and 32/64-bit result boundaries survive the complete request");
    }
    close_session(); no_credentials();
}
void old_no_v2() {
    state.old_no_v2 = true; uint64_t output = untouched; bool supported = true;
    check(allocate(8, output, &supported) == KModErr::OK && !supported && output == untouched &&
          state.calls[MAPLESU_REQUEST_QUERY] == 1 && !state.calls[MAPLESU_REQUEST_SESSION_OPEN],
          "an old image has only the central compatibility selection and no management mutation");
    state.old_no_v2 = false;
    check(allocate(8, output, &supported) == KModErr::OK && supported && output == address &&
          state.calls[MAPLESU_REQUEST_QUERY] == 2, "ambiguous zero QUERY is not permanently cached as an old image");
    close_session(); no_credentials();
}
void cross_thread() {
    open_allocation(8);
    std::thread worker([] {
        check(maplesu_request_select_legacy(state.key.c_str()) == KModErr::OK, "worker explicitly selects its historical credential");
        uint64_t output = untouched; const uint8_t bytes[8]{};
        check(maplesu_request_managed_allocation(address), "another thread knows the managed address provenance");
        check(unsafe_request_kernel_write_mem(address, bytes, sizeof(bytes)) != KModErr::OK,
              "another thread cannot write a task-owned allocation");
        check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched,
              "another thread cannot execute a task-owned allocation");
        check(maplesu_release_staging(address) != 0 && !commands(),
              "another thread cannot release or downgrade the allocation");
        no_credentials();
    });
    worker.join();
    uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
          "the owning thread still executes its untouched resource");
    close_session(); no_credentials();
}
void real_fork() {
    open_allocation(8);
    const pid_t child = fork();
    check(child >= 0, "host process fork succeeds");
    if (!child) {
        ++state.process;
        const auto before = commands(); const auto failed_before = failures.load();
        uint64_t output = untouched;
        check(unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched && commands() == before,
              "real fork does not inherit executable ownership");
        open_allocation(8);
        check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
              "fork child opens and executes its own fresh resource");
        close_session();
        _exit(failures.load() == failed_before ? 0 : 1);
    }
    if (child > 0) {
        int status = 0;
        check(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status),
              "real fork child exits after its independent session lifecycle");
    }
    uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
          "real fork child does not consume the parent's resource");
    close_session(); no_credentials();
}
void credential_success() {
    open_allocation(8);
    const auto before = commands(), prepares = state.prepares, saved_reads = state.saved_reads;
    const auto allocated_handle = state.active_handle, allocated_session = state.active_session;
    check(maplesu_request_key_credential_begin() == KModErr::OK,
          "credential adapter begins against the exact live Key identity");
    state.uid = 0;
    check(maplesu_request_key_credential_end(KModErr::OK) == KModErr::OK && commands() == before,
          "successful credential adapter refreshes only local UID continuity");
    const uint8_t bytes[8] = {1,2,3,4,5,6,7,8}; uint64_t output = untouched;
    check(unsafe_request_kernel_write_mem(address, bytes, sizeof(bytes)) == KModErr::OK,
          "existing allocation accepts WRITE after a completed legitimate UID change");
    check(state.active_handle == allocated_handle && state.active_session == allocated_session &&
          !state.calls[MAPLESU_REQUEST_SESSION_CLOSE] && !state.calls[MAPLESU_REQUEST_RELEASE] &&
          state.calls[MAPLESU_REQUEST_ALLOC] == 1 && state.calls[MAPLESU_REQUEST_SESSION_OPEN] == 1,
          "credential refresh retains the resource and exact session without implicit cleanup");
    check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
          "existing allocation executes through its original handle after UID refresh");
    check(state.prepares == prepares && state.saved_reads == saved_reads,
          "credential continuity does no saved-ID receipt or credential preparation");
    close_session(); no_credentials();
}
void credential_error() {
    open_allocation(8);
    check(maplesu_request_key_credential_begin() == KModErr::OK, "failed credential adapter has a valid begin");
    state.uid = 0; errno = EBUSY;
    const auto before = commands();
    check(maplesu_request_key_credential_end(KModErr::ERR_MODULE_ASM) == KModErr::ERR_MODULE_ASM && errno == EBUSY,
          "credential adapter preserves its original execution error and errno");
    const uint8_t bytes[8]{}; uint64_t output = untouched;
    check(unsafe_request_kernel_write_mem(address, bytes, 8) != KModErr::OK &&
          unsafe_request_kernel_exec_mem(address, 8, output) != KModErr::OK && output == untouched,
          "credential execution error leaves the session uncertain and suppresses output");
    check(allocate(8, output) != KModErr::OK && commands() == before && state.allocated,
          "uncertain credential completion issues no mutation and preserves storage for explicit cleanup");
    close_session(); no_credentials();
}
void credential_identity() {
    for (int changed = 0; changed != 3; ++changed) {
        std::thread worker([changed] {
        check(maplesu_request_select_legacy(state.key.c_str()) == KModErr::OK, "worker explicitly selects its historical credential");
            open_allocation(8);
            check(maplesu_request_key_credential_begin() == KModErr::OK, "identity case starts a real credential adapter");
            if (changed == 0) state.key[47] = 'Z';
            if (changed == 1) ++state.process;
            if (changed == 2) {
                close_session();
                check(maplesu_request_set_auth_mode(RequestAuthMode::LegacyKey) == KModErr::OK,
                      "explicitly reselecting LegacyKey advances its auth generation");
            }
            state.uid = 0; const auto before = commands();
            check(maplesu_request_key_credential_end(KModErr::OK) == KModErr::ERR_MODULE_ROOT_KEY && commands() == before,
                  "credential end never refreshes a changed Key, PID or auth generation");
            no_credentials();
        });
        worker.join();
    }
}
void reused_address() {
    open_allocation(8); uint64_t output = untouched;
    check(unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
          "initial managed allocation is consumed before allocator address reuse");
    state.allow_legacy = true;
    check(unsafe_request_kernel_alloc_mem(8, output) == KModErr::OK && output == address &&
          !maplesu_request_managed_allocation(address),
          "explicit legacy ALLOC can supersede retired managed provenance at the same address");
    const uint8_t bytes[8] = {9,8,7,6,5,4,3,2};
    check(unsafe_request_kernel_write_mem(address, bytes, 8) == KModErr::OK &&
          state.legacy_allocs == 1 && state.legacy_writes == 8,
          "same-address legacy allocation uses the centralized byte WRITE adapter");
    state.allocated = false; // Old module_memfree has no SDK RELEASE notification.
    open_allocation(8);
    const auto before = state.legacy;
    check(unsafe_request_kernel_write_mem(address, bytes, 8) == KModErr::OK &&
          unsafe_request_kernel_exec_mem(address, 8, output) == KModErr::OK && output == answer,
          "newest managed allocation at that address writes and executes its new handle");
    check(state.legacy == before && state.calls[MAPLESU_REQUEST_ALLOC] == 2 &&
          state.calls[MAPLESU_REQUEST_WRITE] == 1 && state.calls[MAPLESU_REQUEST_EXEC] == 2,
          "new managed provenance removes stale legacy selection and never downgrades");
    close_session();
}
void end_error(uint32_t command) {
    if (command != MAPLESU_REQUEST_ALLOC) open_allocation(8);
    state.fail_command = command; state.status = -ESTALE;
    state.effects = MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_SESSION_CLOSED;
    if (command == MAPLESU_REQUEST_ALLOC) {
        state.failed_result = address; state.failed_handle = state.active_handle + 1;
        state.effects |= MAPLESU_EFFECT_ALLOCATED;
    } else if (command == MAPLESU_REQUEST_WRITE) {
        state.failed_result = 8; state.effects |= MAPLESU_EFFECT_WRITTEN;
    } else {
        state.failed_result = answer; state.effects |= MAPLESU_EFFECT_EXECUTED | MAPLESU_EFFECT_QUARANTINED;
    }
    const uint8_t bytes[8]{}; uint64_t output = untouched;
    const auto error = command == MAPLESU_REQUEST_ALLOC ? allocate(8, output) :
        command == MAPLESU_REQUEST_WRITE ? unsafe_request_kernel_write_mem(address, bytes, 8) :
        unsafe_request_kernel_exec_mem(address, 8, output);
    check(error != KModErr::OK && output == untouched && errno == ESTALE,
          "native END error preserves errno and prevents publishing command output");
    const auto receipt = maplesu_request_management_reply();
    check(receipt.completed && receipt.command == command && receipt.status == -ESTALE &&
          receipt.result == state.failed_result && receipt.handle == state.failed_handle && receipt.effects == state.effects,
          "native END error retains the command result, handle and complete session cleanup effects");
    const auto before = commands();
    check(allocate(8, output) != KModErr::OK && commands() == before,
          "native END closed completion never silently opens or replays a mutation");
    state.fail_command = 0; state.status = 0;
    close_session(); no_credentials();
}
}

namespace kernel_module { uint32_t get_page_size() { return 4096; } }
extern "C" int64_t maplesu_prepare_kernel_request() noexcept { ++state.prepares; return 0; }
extern "C" const char* __wrap___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW() {
    return state.key.c_str();
}
extern "C" pid_t __wrap_getpid() { return state.process; }
extern "C" uid_t __wrap_getuid() { return state.uid; }
extern "C" uid_t __wrap_geteuid() { return state.uid; }
extern "C" void* __real__Znwm(size_t);
extern "C" void* __wrap__Znwm(size_t size) {
    if (state.fail_new) { state.fail_new = false; throw std::bad_alloc(); }
    return __real__Znwm(size);
}
extern "C" int __wrap_getresuid(uid_t* real, uid_t* effective, uid_t* saved) {
    ++state.saved_reads; *real = *effective = state.uid; *saved = state.saved_uid; return 0;
}
extern "C" int __wrap_getresgid(gid_t* real, gid_t* effective, gid_t* saved) {
    ++state.saved_reads; *real = *effective = 1000; *saved = state.saved_gid; return 0;
}
extern "C" long __wrap_syscall(long number, ...) {
    check(number == 221, "actual client uses the installed syscall envelope");
    va_list args; va_start(args, number);
    const auto* packet = va_arg(args, const uint8_t*); va_end(args);
    check(!std::memcmp(packet, state.key.data(), 48) && packet[84] == 0, "complete immutable Key packet");
    std::array<uint8_t, 18> frame{};
    for (size_t i = 0; i < frame.size(); ++i) frame[i] = packet[48 + i] + packet[66 + i];
    if (frame[0] != MAPLESU_REQUEST_SELECTOR_FLAG || frame[1] != MAPLESU_REQUEST_SELECTOR_SUB) {
        ++state.legacy;
        check(state.allow_legacy, "legacy mutation requires the explicit compatibility scenario");
        uint64_t legacy_address = 0; uint32_t legacy_size = 0, legacy_pages = 0;
        std::memcpy(&legacy_address, frame.data() + 2, sizeof(legacy_address));
        std::memcpy(&legacy_size, frame.data() + 10, sizeof(legacy_size));
        std::memcpy(&legacy_pages, frame.data() + 14, sizeof(legacy_pages));
        if (frame[0] == static_cast<uint8_t>(KernelCmdFlag::ALLOC_MEM)) {
            check(!legacy_address && legacy_size == 4096 && legacy_pages == 1,
                  "legacy allocation retains page-aligned 32-bit extent framing");
            ++state.legacy_allocs; state.allocated = true; state.memory.assign(legacy_size, 0);
            state.saved_uid = static_cast<uint32_t>(address); state.saved_gid = static_cast<uint32_t>(address >> 32);
        } else if (frame[0] == static_cast<uint8_t>(KernelCmdFlag::WRITE_MEM)) {
            check(state.allocated && legacy_address >= address && legacy_address - address < state.memory.size(),
                  "legacy byte WRITE addresses its explicitly allocated memory");
            ++state.legacy_writes;
            if (legacy_address >= address && legacy_address - address < state.memory.size())
                state.memory[legacy_address - address] = frame[1];
        } else check(false, "compatibility scenario uses only legacy ALLOC and byte WRITE");
        return -1;
    }
    uint64_t pointer = 0; std::memcpy(&pointer, frame.data() + 10, sizeof(pointer));
    const auto request = *reinterpret_cast<const maplesu_request_v2*>(static_cast<uintptr_t>(pointer));
    check(pointer > UINT32_MAX && request.response_user > UINT32_MAX,
          "request and response user pointers preserve all 64-bit address bits");
    check(request.magic == MAPLESU_REQUEST_MAGIC && request.version == 2 && request.length == sizeof(request) &&
          !request.reserved && request.command < state.calls.size(), "exact v2 request header and command");
    if (request.command >= state.calls.size()) return -1;
    ++state.calls[request.command];
    { std::lock_guard<std::mutex> lock(id_mutex); check(ids.insert(request.request_id).second, "request IDs are unique across threads"); }
    maplesu_response_v2 response{};
    response.completion_magic = MAPLESU_RESPONSE_MAGIC; response.version = 2; response.length = sizeof(response);
    response.command = request.command; response.request_id = request.request_id;
    const bool failing = request.command == state.fail_command;
    if (request.command == MAPLESU_REQUEST_QUERY) {
        if (state.old_no_v2) return -1;
        response.result = MAPLESU_REQUEST_CAPABILITIES;
        response.metadata_address = address; response.metadata_length = sizeof(maplesu_metadata_v2);
        response.metadata_version = MAPLESU_METADATA_VERSION;
    } else if (request.command == MAPLESU_REQUEST_READ) {
        const auto read = *reinterpret_cast<const maplesu_read_v2*>(static_cast<uintptr_t>(request.arg1));
        check(read.length && read.length <= MAPLESU_READ_MAX_BYTES && !read.reserved && request.arg0 >= address &&
              request.arg0 - address + read.length <= state.memory.size(), "READ uses the bounded actual v2 descriptor");
        if (request.arg0 >= address && request.arg0 - address + read.length <= state.memory.size())
            std::memcpy(reinterpret_cast<void*>(static_cast<uintptr_t>(read.destination_user)),
                        state.memory.data() + request.arg0 - address, read.length);
        response.result = read.length;
    } else {
        check(request.arg0 == 0 && request.arg1, "management descriptor stays in v2 arg1");
        const auto op = *reinterpret_cast<const maplesu_management_v3*>(static_cast<uintptr_t>(request.arg1));
        check(!op.flags && !op.reserved, "ordinary memory management has zero flags/reserved");
        if (request.command == MAPLESU_REQUEST_SESSION_OPEN) {
            check(!op.host.low && !op.host.high && !op.session && !op.handle && !op.address &&
                  op.length == sizeof(host) && op.data_user, "OPEN supplies only the explicit host output");
            if (!state.old_v2) {
                std::memcpy(reinterpret_cast<void*>(static_cast<uintptr_t>(op.data_user)), &host, sizeof(host));
                response.result = ++state.active_session;
            } else {
                response.status = MAPLESU_REQUEST_STATUS_UNSUPPORTED;
                if (state.nonzero_unsupported) response.result = 7;
            }
        } else {
            const bool close_unknown = request.command == MAPLESU_REQUEST_SESSION_CLOSE && !op.session &&
                !op.host.low && !op.host.high;
            check(close_unknown || (same_host(op.host) && (op.session == state.active_session ||
                  (request.command == MAPLESU_REQUEST_SESSION_CLOSE && !op.session))), "real host/session bind every management mutation");
            switch (request.command) {
            case MAPLESU_REQUEST_ALLOC:
                check(!op.handle && !op.address && !op.data_user && op.length, "owned ALLOC descriptor");
                if (!failing || !state.status || (state.effects & MAPLESU_EFFECT_ALLOCATED)) {
                    state.allocated = true; state.memory.assign(op.length, 0);
                    response.result = address; response.metadata_address = ++state.active_handle;
                    response.metadata_length = MAPLESU_EFFECT_ALLOCATED;
                }
                break;
            case MAPLESU_REQUEST_WRITE:
                check(state.allocated && op.handle == state.active_handle && op.data_user && op.address + op.length <= state.memory.size(),
                      "WRITE resolves its allocation handle and offset");
                if (!failing || !state.status || (state.effects & MAPLESU_EFFECT_WRITTEN)) {
                    std::memcpy(state.memory.data() + op.address,
                        reinterpret_cast<const void*>(static_cast<uintptr_t>(op.data_user)), op.length);
                    response.result = op.length; response.metadata_length = MAPLESU_EFFECT_WRITTEN;
                }
                break;
            case MAPLESU_REQUEST_EXEC:
                check(state.allocated && op.handle == state.active_handle && !op.address && !op.data_user && op.length == state.memory.size(),
                      "EXEC consumes the exact owned allocation");
                if (!failing || !state.status || (state.effects & MAPLESU_EFFECT_EXECUTED)) ++state.execs;
                if (!failing || !state.status || (state.effects & (MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_QUARANTINED)))
                    state.allocated = false;
                response.result = state.return_value; response.metadata_length = MAPLESU_EFFECT_EXECUTED | MAPLESU_EFFECT_RELEASED;
                break;
            case MAPLESU_REQUEST_RELEASE:
                check(state.allocated && op.handle == state.active_handle && !op.address && !op.data_user && !op.length,
                      "RELEASE consumes the actual allocation handle");
                state.allocated = false; response.metadata_length = MAPLESU_EFFECT_RELEASED;
                break;
            case MAPLESU_REQUEST_SESSION_CLOSE:
                state.allocated = false; response.metadata_length = MAPLESU_EFFECT_SESSION_CLOSED;
                break;
            default: check(false, "no unrelated command enters the fixture");
            }
        }
    }
    if (failing) {
        if (state.status) {
            response.status = state.status; response.result = state.failed_result;
            response.metadata_address = state.failed_handle; response.metadata_length = state.effects;
            if (state.effects & MAPLESU_EFFECT_SESSION_CLOSED) state.allocated = false;
        }
        if (state.mutate_key) state.key[47] = 'Z';
        if (state.mutate_process) ++state.process;
        switch (state.completion) {
        case Completion::Missing: return -1;
        case Completion::Magic: response.completion_magic ^= 1; break;
        case Completion::Version: response.version = 3; break;
        case Completion::Length: --response.length; break;
        case Completion::Command: ++response.command; break;
        case Completion::Request: ++response.request_id; break;
        case Completion::Positive: response.status = 1; break;
        default: break;
        }
    }
    std::memcpy(reinterpret_cast<void*>(static_cast<uintptr_t>(request.response_user)), &response, sizeof(response));
    return -1;
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    check(kernel_module::maplesu_request_select_legacy(state.key.c_str()) == KModErr::OK, "explicit historical client protocol");
    const std::string name = argv[1];
    if (name == "roundtrip") roundtrip();
    else if (name == "execute") execute();
    else if (name == "release") release();
    else if (name == "bounds") bounds();
    else if (name == "open-unknown") open_unknown();
    else if (name == "exec-unknown") exec_unknown();
    else if (name == "negative") negative();
    else if (name == "exec-effect-error") exec_effect_error();
    else if (name == "release-error") release_error();
    else if (name == "close-error") close_error();
    else if (name == "key-change") key_change();
    else if (name == "fork") fork_identity();
    else if (name == "old-v2") old_v2(false);
    else if (name == "bad-unsupported") old_v2(true);
    else if (name == "bad-completion") bad_completion();
    else if (name == "threads") threads();
    else if (name == "allocation-failure") allocation_failure();
    else if (name == "write-error-cleanup") execution_failure(MAPLESU_REQUEST_WRITE, false);
    else if (name == "exec-error-cleanup") execution_failure(MAPLESU_REQUEST_EXEC, false);
    else if (name == "write-unknown") execution_failure(MAPLESU_REQUEST_WRITE, true);
    else if (name == "close-unknown") close_unknown();
    else if (name == "closed-address") closed_address();
    else if (name == "return-bits") return_bits();
    else if (name == "old-no-v2") old_no_v2();
    else if (name == "cross-thread") cross_thread();
    else if (name == "real-fork") real_fork();
    else if (name == "credential-success") credential_success();
    else if (name == "credential-error") credential_error();
    else if (name == "credential-identity") credential_identity();
    else if (name == "reused-address") reused_address();
    else if (name == "end-write-error") end_error(MAPLESU_REQUEST_WRITE);
    else if (name == "end-alloc-error") end_error(MAPLESU_REQUEST_ALLOC);
    else if (name == "end-exec-error") end_error(MAPLESU_REQUEST_EXEC);
    else return 2;
    std::printf("Key management %s checks=%u failures=%u\n", name.c_str(), checks.load(), failures.load());
    return failures.load() ? 1 : 0;
}
