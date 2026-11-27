#include "module_base_kernel_request.h"
#include "maplesu_request_private.h"

#include <array>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr uint64_t kernel = UINT64_C(0xffff000012340000);
struct Fixture {
    std::string key = std::string(48, 'K');
    uint64_t capabilities = MAPLESU_REQUEST_CAPABILITIES;
    pid_t process = 431;
    int prepares = 0, ids = 0, queries = 0, reads = 0;
    int32_t status = 0;
    bool untouched = false, bad_response = false, short_result = false;
    bool change_identity_on_read = false;
    const uint8_t* nofault_unwritable = nullptr;
    std::vector<uint32_t> lengths;
};
thread_local Fixture state;
thread_local bool fail_next_allocation = false;
std::atomic<int> checks{0}, failures{0};
std::atomic<unsigned> identities{0};
void check(bool value, const char* message) {
    ++checks;
    if (!value) { ++failures; std::fprintf(stderr, "FAIL: %s\n", message); }
}
void reset() {
    state = {};
    kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::LegacyKey);
    const auto id = identities.fetch_add(1);
    char number[17];
    std::snprintf(number, sizeof(number), "%016x", id);
    state.key.replace(0, 16, number);
}
uint8_t data(uint64_t address) { return static_cast<uint8_t>((address ^ (address >> 9)) * 37); }
KModErr read_private(uint64_t source, void* destination, uint32_t size) {
    return kernel_module::maplesu_request_v2_read(source, destination, size);
}
}

namespace kernel_module { uint32_t get_page_size() { return 4096; } }
extern "C" int64_t maplesu_prepare_kernel_request() noexcept { ++state.prepares; return 0; }
extern "C" const char* __wrap___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW() {
    return state.key.c_str();
}
extern "C" pid_t __wrap_getpid() { return state.process; }
extern "C" void* __real__Znwm(size_t);
extern "C" void* __wrap__Znwm(size_t size) {
    if (fail_next_allocation) { fail_next_allocation = false; throw std::bad_alloc(); }
    return __real__Znwm(size);
}
extern "C" int __wrap_getresuid(uid_t* real, uid_t* effective, uid_t* saved) {
    ++state.ids; *real = *effective = 1000; *saved = 1234; return 0;
}
extern "C" int __wrap_getresgid(gid_t* real, gid_t* effective, gid_t* saved) {
    ++state.ids; *real = *effective = 1000; *saved = 5678; return 0;
}
extern "C" long __wrap_syscall(long number, ...) {
    check(number == 221, "request uses production transport");
    va_list args; va_start(args, number);
    const auto* packet = va_arg(args, const unsigned char*); va_end(args);
    check(!std::memcmp(packet, state.key.data(), 48) && packet[84] == 0, "authenticated packet");
    std::array<unsigned char, 18> frame{};
    for (size_t i = 0; i < frame.size(); ++i) frame[i] = packet[48 + i] + packet[66 + i];
    check(frame[0] == MAPLESU_REQUEST_SELECTOR_FLAG && frame[1] == MAPLESU_REQUEST_SELECTOR_SUB,
          "READ uses the same selector");
    uint64_t pointer = 0;
    std::memcpy(&pointer, frame.data() + 10, sizeof(pointer));
    const auto request = *reinterpret_cast<const maplesu_request_v2*>(static_cast<uintptr_t>(pointer));
    if (request.command == MAPLESU_REQUEST_QUERY) ++state.queries;
    if (state.untouched) return -1;
    maplesu_response_v2 response{};
    response.version = 2; response.length = sizeof(response);
    response.command = request.command; response.request_id = request.request_id;
    response.status = state.status;
    if (request.command == MAPLESU_REQUEST_QUERY) {
        response.result = state.capabilities;
        response.metadata_address = kernel; response.metadata_length = 48; response.metadata_version = 2;
    } else {
        check(request.command == MAPLESU_REQUEST_READ, "private read command");
        const auto descriptor = *reinterpret_cast<const maplesu_read_v2*>(static_cast<uintptr_t>(request.arg1));
        check(descriptor.length > 0 && descriptor.length <= MAPLESU_READ_MAX_BYTES && !descriptor.reserved,
              "actual READ descriptor has bounded length and no reserved bits");
        ++state.reads; state.lengths.push_back(descriptor.length);
        if (state.nofault_unwritable && descriptor.destination_user >= reinterpret_cast<uintptr_t>(state.nofault_unwritable) &&
            descriptor.destination_user < reinterpret_cast<uintptr_t>(state.nofault_unwritable + 1024))
            response.status = MAPLESU_REQUEST_STATUS_FAULT;
        if (!response.status) {
            auto* output = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(descriptor.destination_user));
            for (uint32_t i = 0; i < descriptor.length; ++i) output[i] = data(request.arg0 + i);
            response.result = descriptor.length - (state.short_result ? 1 : 0);
        }
    }
    response.completion_magic = state.bad_response ? 0 : MAPLESU_RESPONSE_MAGIC;
    std::memcpy(reinterpret_cast<void*>(static_cast<uintptr_t>(request.response_user)), &response, sizeof(response));
    if (request.command == MAPLESU_REQUEST_READ && state.change_identity_on_read) {
        state.key[47] = 'Z';
        state.change_identity_on_read = false;
    }
    return -1;
}

int main() {
    std::array<uint8_t, 1024> bytes{};
    for (uint32_t size : {1u, 255u, 256u, 257u, 512u, 513u, 1024u}) {
        reset(); bytes.fill(0);
        check(read_private(kernel, bytes.data(), size) == KModErr::OK, "real client copies requested length");
        for (uint32_t i = 0; i < size; ++i) check(bytes[i] == data(kernel + i), "READ bytes retain order");
        check(state.queries == 1 && state.prepares == 1 && state.ids == 2, "initial channel probes once");
        check(state.reads == static_cast<int>((size + 255) / 256), "bounded batches cover exact size");
        const auto count = state.reads;
        check(read_private(kernel + 11, bytes.data(), 17) == KModErr::OK && state.reads == count + 1 &&
              state.queries == 1 && state.prepares == 1 && state.ids == 2, "warm READ has zero credential work");
    }
    reset(); bytes.fill(0x5a); state.nofault_unwritable = bytes.data();
    check(read_private(kernel, bytes.data(), bytes.size()) == KModErr::OK && state.reads == 4,
          "nofault producer receives private batch staging, not caller COW/lazy heap pages");
    for (uint32_t i = 0; i < bytes.size(); ++i)
        check(bytes[i] == data(kernel + i), "staged READ publishes the exact completed bytes");
    for (auto source : {uint64_t(0), kernel >> 32, UINT64_MAX}) {
        reset(); check(read_private(source, bytes.data(), 2) == KModErr::ERR_MODULE_PARAM && !state.queries,
                       "bad or wrapping kernel range rejected before transport");
    }
    reset();
    check(read_private(kernel, nullptr, 1) == KModErr::ERR_MODULE_PARAM &&
          read_private(kernel, bytes.data(), 0) == KModErr::ERR_MODULE_PARAM &&
          read_private(kernel, reinterpret_cast<void*>(std::numeric_limits<uintptr_t>::max()), 2) == KModErr::ERR_MODULE_PARAM &&
          !state.queries, "null, zero and wrapping user range rejected before transport");
    reset(); state.capabilities = MAPLESU_CAP_QUERY | MAPLESU_CAP_ECHO64;
    check(read_private(kernel, bytes.data(), 1) == KModErr::ERR_MODULE_TEST_CHANNEL && state.queries == 1 && !state.reads,
          "missing READ capability never sends READ or retries legacy");
    reset(); state.untouched = true;
    check(read_private(kernel, bytes.data(), 1) == KModErr::ERR_MODULE_TEST_CHANNEL && state.queries == 1, "empty probe is not READ");
    state.untouched = false;
    check(read_private(kernel, bytes.data(), 1) == KModErr::OK && state.queries == 2, "empty probe is never cached as legacy");
    reset(); state.bad_response = true;
    check(read_private(kernel, bytes.data(), 1) == KModErr::ERR_MODULE_TEST_CHANNEL && !state.reads, "bad negotiation stops read");
    state.bad_response = false;
    check(read_private(kernel, bytes.data(), 1) == KModErr::OK && state.queries == 2, "failed negotiation is not cached");
    for (const auto status : {MAPLESU_REQUEST_STATUS_INVALID, MAPLESU_REQUEST_STATUS_FAULT, MAPLESU_REQUEST_STATUS_UNSUPPORTED}) {
        reset(); maplesu_response_v2 channel{}; bool supported = false;
        check(kernel_module::maplesu_request_v2_channel(channel, supported) == KModErr::OK && supported, "negotiate error fixture");
        state.status = status;
        const auto expected = status == MAPLESU_REQUEST_STATUS_INVALID ? KModErr::ERR_MODULE_PARAM :
            status == MAPLESU_REQUEST_STATUS_FAULT ? KModErr::ERR_MODULE_NOT_KADDR : KModErr::ERR_MODULE_TEST_CHANNEL;
        check(read_private(kernel, bytes.data(), 300) == expected && state.reads == 1 && state.queries == 1,
              "command failure preserves cause without second batch or protocol retry");
    }
    reset(); state.short_result = true;
    check(read_private(kernel, bytes.data(), 300) == KModErr::ERR_MODULE_TEST_CHANNEL && state.reads == 1,
          "short result stops subsequent copies");
    reset(); check(read_private(kernel, bytes.data(), 1) == KModErr::OK, "identity fixture initialized");
    state.key[47] = 'Z';
    check(read_private(kernel, bytes.data(), 1) == KModErr::OK && state.queries == 2, "same-length identity change invalidates channel");
    ++state.process;
    check(read_private(kernel, bytes.data(), 1) == KModErr::OK && state.queries == 3, "fork process invalidates inherited channel");
    reset(); fail_next_allocation = true;
    check(read_private(kernel, bytes.data(), 1) == KModErr::ERR_NO_MEM && !state.queries,
          "probe packet allocation failure becomes SDK error before syscall");
    check(read_private(kernel, bytes.data(), 1) == KModErr::OK && state.queries == 1,
          "failed allocation is not cached as a negotiated channel");
    fail_next_allocation = true;
    check(read_private(kernel, bytes.data(), 1) == KModErr::ERR_NO_MEM && state.reads == 1 && state.queries == 1,
          "warm READ packet allocation failure does not throw or retry");
    reset(); state.change_identity_on_read = true; bytes.fill(0);
    check(read_private(kernel, bytes.data(), 300) == KModErr::ERR_MODULE_ROOT_KEY && state.reads == 1 &&
          state.queries == 1, "identity change stops a multi-batch read without replay");
    for (uint32_t i = 0; i < 300; ++i)
        check(bytes[i] == 0,
              "identity failure never publishes the tentative batch or reaches the later batch");
    check(read_private(kernel, bytes.data(), 300) == KModErr::OK && state.queries == 2 && state.reads == 3,
          "next explicit read negotiates the changed identity");
    std::vector<std::thread> workers;
    for (int i = 0; i < 16; ++i) workers.emplace_back([] {
        reset(); std::array<uint8_t, 300> output{};
        for (int n = 0; n < 30; ++n) check(read_private(kernel + n, output.data(), output.size()) == KModErr::OK, "parallel read");
        check(state.queries == 1 && state.prepares == 1 && state.reads == 60, "each thread negotiates once");
    });
    for (auto& worker : workers) worker.join();
    std::printf("READ checks=%d failures=%d\n", checks.load(), failures.load());
    return failures.load() ? 1 : 0;
}
