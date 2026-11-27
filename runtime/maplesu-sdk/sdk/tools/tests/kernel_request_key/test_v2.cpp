#include "module_base_kernel_request.h"
#include "maplesu_request_private.h"

#include <array>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

namespace {
enum class Reply { valid, untouched, partial, magic, version, length, command, id,
                   capabilities, metadata_address, metadata_length, metadata_version };
struct Fixture {
    Reply reply = Reply::valid;
    int prepare_calls = 0, uid_reads = 0, gid_reads = 0, syscalls = 0;
    int64_t prepare_error = 0;
    bool uid_error = false, gid_error = false;
    uint32_t suid = 1234, sgid = 5678;
    int32_t status = 0;
    const char* key = "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK";
    const char* key_after_prepare = nullptr;
    const char* key_after_uid = nullptr;
    const char* key_after_gid = nullptr;
    const char* key_after_syscall = nullptr;
    bool clear_key_after_syscall = false, change_key_on_allocation = false;
    std::array<char, 49> mutable_key{};
    const char* expected_packet_key = nullptr;
};
thread_local Fixture state;
std::atomic<int> checks{0}, failures{0};
std::mutex ids_lock;
std::unordered_set<uint64_t> ids;
void check(bool value, const char* message) {
    ++checks;
    if (!value) { ++failures; std::fprintf(stderr, "FAIL: %s\n", message); }
}
void reset() {
    state = {};
    kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::LegacyKey);
}
KModErr probe(bool& supported, maplesu_response_v2& response) {
    return kernel_module::maplesu_request_v2_probe(response, supported);
}
}

namespace kernel_module { uint32_t get_page_size() { return 4096; } }
extern "C" int64_t maplesu_prepare_kernel_request() noexcept {
    ++state.prepare_calls;
    if (state.key_after_prepare) state.key = state.key_after_prepare;
    return state.prepare_error;
}
extern "C" const char* __wrap___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW() {
    return state.key;
}
extern "C" void* __real__Znwm(size_t);
extern "C" void* __wrap__Znwm(size_t size) {
    if (state.change_key_on_allocation) {
        state.change_key_on_allocation = false;
        state.mutable_key[0] = 'Z';
    }
    return __real__Znwm(size);
}
extern "C" int __wrap_getresuid(uid_t* real, uid_t* effective, uid_t* saved) {
    ++state.uid_reads;
    *real = *effective = 1000; *saved = state.suid;
    if (state.key_after_uid) state.key = state.key_after_uid;
    return state.uid_error ? -1 : 0;
}
extern "C" int __wrap_getresgid(gid_t* real, gid_t* effective, gid_t* saved) {
    ++state.gid_reads;
    *real = *effective = 1000; *saved = state.sgid;
    if (state.key_after_gid) state.key = state.key_after_gid;
    return state.gid_error ? -1 : 0;
}
extern "C" long __wrap_syscall(long number, ...) {
    ++state.syscalls;
    check(number == 221, "actual AArch64 request syscall");
    va_list args;
    va_start(args, number);
    const auto* packet = va_arg(args, const unsigned char*);
    va_end(args);
    check(!std::memcmp(packet, state.expected_packet_key ? state.expected_packet_key : state.key, 48) &&
          packet[84] == 0, "authenticated packet and terminator");
    std::array<unsigned char, 18> frame{};
    for (size_t i = 0; i < frame.size(); ++i) frame[i] = packet[48 + i] + packet[66 + i];
    check(frame[0] == MAPLESU_REQUEST_SELECTOR_FLAG && frame[1] == MAPLESU_REQUEST_SELECTOR_SUB,
          "shared v2 selector");
    uint64_t saved = 0, pointer = 0;
    std::memcpy(&saved, frame.data() + 2, 8);
    std::memcpy(&pointer, frame.data() + 10, 8);
    if (state.prepare_calls) {
        check(saved == (uint64_t(state.sgid) << 32 | state.suid), "legacy probe retains original saved IDs");
    }
    const auto request = *reinterpret_cast<const maplesu_request_v2*>(static_cast<uintptr_t>(pointer));
    check(request.magic == MAPLESU_REQUEST_MAGIC && request.version == 2 && request.length == 48 &&
          request.reserved == 0 && request.request_id != 0 && request.arg1 == 0,
          "actual request snapshot matches shared contract");
    {
        std::lock_guard<std::mutex> lock(ids_lock);
        check(ids.insert(request.request_id).second, "request IDs remain unique across threads");
    }
    if (state.reply == Reply::untouched) {
        // The old selector only writes the supplied saved IDs, never a response.
        state.suid = uint32_t(saved); state.sgid = uint32_t(saved >> 32);
        return -1;
    }
    maplesu_response_v2 response{};
    response.version = 2; response.length = 48;
    response.command = request.command; response.request_id = request.request_id;
    response.status = state.status;
    response.result = request.command == MAPLESU_REQUEST_QUERY ? MAPLESU_REQUEST_CAPABILITIES : request.arg0;
    if (request.command == MAPLESU_REQUEST_QUERY) {
        response.metadata_address = 0xffff000012345678ull;
        response.metadata_length = 48; response.metadata_version = 2;
    }
    response.completion_magic = MAPLESU_RESPONSE_MAGIC;
    switch (state.reply) {
        case Reply::partial: response.completion_magic = 0; break;
        case Reply::magic: response.completion_magic ^= 1; break;
        case Reply::version: ++response.version; break;
        case Reply::length: --response.length; break;
        case Reply::command: ++response.command; break;
        case Reply::id: ++response.request_id; break;
        case Reply::capabilities: response.result = 0; break;
        case Reply::metadata_address: response.metadata_address = 0; break;
        case Reply::metadata_length: --response.metadata_length; break;
        case Reply::metadata_version: ++response.metadata_version; break;
        default: break;
    }
    std::memcpy(reinterpret_cast<void*>(static_cast<uintptr_t>(request.response_user)), &response, sizeof(response));
    if (state.key_after_syscall) state.key = state.key_after_syscall;
    if (state.clear_key_after_syscall) state.key = nullptr;
    return -1; // The syscall result is unrelated to the response result/status.
}

int main() {
    maplesu_response_v2 response{};
    bool supported = false;
    reset();
    check(probe(supported, response) == KModErr::OK && supported && response.result == MAPLESU_REQUEST_CAPABILITIES,
          "new gateway QUERY accepted");
    check(state.prepare_calls == 1 && state.uid_reads == 1 && state.gid_reads == 1 && state.syscalls == 1 &&
          state.suid == 1234 && state.sgid == 5678, "first probe prepares once and preserves credentials");
    reset(); state.reply = Reply::untouched;
    supported = true;
    check(probe(supported, response) == KModErr::OK && !supported && response.completion_magic == 0 &&
          state.suid == 1234 && state.sgid == 5678 && state.syscalls == 1,
          "old gateway is detected with no side effects or second request");
    for (const auto bad : {Reply::partial, Reply::magic, Reply::version, Reply::length, Reply::command, Reply::id,
                           Reply::capabilities, Reply::metadata_address, Reply::metadata_length, Reply::metadata_version}) {
        reset(); state.reply = bad; supported = true;
        check(probe(supported, response) == KModErr::ERR_MODULE_TEST_CHANNEL && !supported && state.syscalls == 1,
              "damaged or incomplete QUERY fails without fallback or retry");
    }
    for (size_t length : {size_t(0), size_t(16), size_t(47), size_t(49), size_t(1024)}) {
        reset(); const std::string key(length, 'K'); state.key = key.c_str(); supported = true;
        check(probe(supported, response) == KModErr::ERR_MODULE_ROOT_KEY && !supported && state.prepare_calls == 0 &&
              state.syscalls == 0, "gateway key length checked before preparation");
    }
    reset(); state.key = nullptr;
    check(probe(supported, response) == KModErr::ERR_MODULE_ROOT_KEY && state.syscalls == 0, "null key rejected");
    reset(); state.prepare_error = static_cast<int64_t>(KModErr::ERR_MODULE_CRED_DANGER);
    check(probe(supported, response) == KModErr::ERR_MODULE_CRED_DANGER && state.uid_reads == 0 && state.syscalls == 0,
          "prepare error remains exact and prevents transport");
    for (bool uid : {true, false}) {
        reset(); state.uid_error = uid; state.gid_error = !uid;
        check(probe(supported, response) == KModErr::ERR_MODULE_CRED_DANGER && state.syscalls == 0,
              "failed saved-ID snapshot stops compatibility probe");
    }
    for (const uint64_t value : std::array<uint64_t, 5>{0, UINT64_MAX, uint64_t(1) << 63, uint64_t(-19999), 0x1234567887654321ull}) {
        reset();
        check(kernel_module::maplesu_request_v2_exchange(MAPLESU_REQUEST_ECHO64, value, 0, response) == KModErr::OK &&
              response.result == value && response.status == 0 && response.metadata_address == 0,
              "ECHO64 preserves every bit independently of historical sentinel values");
        check(state.prepare_calls == 0 && state.uid_reads == 0 && state.gid_reads == 0 && state.syscalls == 1,
              "negotiated exchange has no credential or preparation calls");
    }
    reset(); state.status = -95;
    check(kernel_module::maplesu_request_v2_exchange(99, UINT64_MAX, 0, response) == KModErr::OK &&
          response.status == -95 && response.result == UINT64_MAX && state.syscalls == 1,
          "transport, command status and full result are independent");
    reset(); state.reply = Reply::untouched;
    check(kernel_module::maplesu_request_v2_exchange(MAPLESU_REQUEST_ECHO64, 0, 0, response) ==
          KModErr::ERR_MODULE_TEST_CHANNEL && state.syscalls == 1, "incomplete exchange never renegotiates or retries");
    constexpr const char* other_key = "ZKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK";
    const maplesu_response_v2 empty{};
    for (unsigned stage = 0; stage < 3; ++stage) {
        reset();
        if (stage == 0) state.key_after_prepare = other_key;
        if (stage == 1) state.key_after_uid = other_key;
        if (stage == 2) state.key_after_gid = other_key;
        std::memset(&response, 0xA5, sizeof(response)); supported = true;
        check(probe(supported, response) == KModErr::ERR_MODULE_ROOT_KEY && !supported &&
              !std::memcmp(&response, &empty, sizeof(response)) && state.syscalls == 0,
              "changed identity during compatibility preparation stops before transport");
    }
    for (const char* changed : {other_key, "short"}) {
        reset(); state.key_after_syscall = changed;
        std::memset(&response, 0xA5, sizeof(response));
        check(kernel_module::maplesu_request_v2_exchange(MAPLESU_REQUEST_ECHO64, UINT64_MAX, 0, response) ==
              KModErr::ERR_MODULE_ROOT_KEY && !std::memcmp(&response, &empty, sizeof(response)) && state.syscalls == 1 &&
              state.prepare_calls == 0, "identity change discards completed response without replay");
    }
    for (unsigned kind = 0; kind < 3; ++kind) {
        reset();
        if (kind == 0) state.key_after_syscall = other_key;
        if (kind == 1) state.clear_key_after_syscall = true;
        if (kind == 2) state.key_after_syscall = "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK";
        std::memset(&response, 0xA5, sizeof(response)); supported = true;
        check(probe(supported, response) == KModErr::ERR_MODULE_ROOT_KEY && !supported &&
              !std::memcmp(&response, &empty, sizeof(response)) && state.syscalls == 1,
              "probe discards completed response after content, null or length identity change");
    }
    reset();
    std::memcpy(state.mutable_key.data(), state.key, state.mutable_key.size());
    state.expected_packet_key = state.key;
    state.key = state.mutable_key.data();
    state.change_key_on_allocation = true;
    std::memset(&response, 0xA5, sizeof(response));
    check(kernel_module::maplesu_request_v2_exchange(MAPLESU_REQUEST_ECHO64, UINT64_MAX, 0, response) ==
          KModErr::ERR_MODULE_ROOT_KEY && !state.change_key_on_allocation && state.syscalls == 1 &&
          !std::memcmp(&response, &empty, sizeof(response)),
          "packet uses starting identity even when current Key changes during packet allocation");
    std::vector<std::thread> threads;
    for (int thread = 0; thread < 16; ++thread) threads.emplace_back([thread] {
        reset();
        for (int i = 0; i < 100; ++i) {
            maplesu_response_v2 own{};
            const uint64_t value = (uint64_t(thread) << 48) | (uint64_t(i) << 32) | 0x87654321;
            check(kernel_module::maplesu_request_v2_exchange(MAPLESU_REQUEST_ECHO64, value, 0, own) == KModErr::OK &&
                  own.result == value, "concurrent requests retain independent response storage");
        }
    });
    for (auto& thread : threads) thread.join();
    std::printf("kernel-request-v2: %d checks, %d failures, %zu unique IDs\n", checks.load(), failures.load(), ids.size());
    return failures ? 1 : 0;
}
