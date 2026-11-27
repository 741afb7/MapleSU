#include "module_base_kernel_request.h"
#include "maplesu_request_private.h"

#include <cstdarg>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

extern "C" void __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(const char*);
extern "C" const char* __real___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
namespace kernel_module {
extern "C" KModErr maplesu_legacy_request_kernel_read_mem(uint64_t, void*, uint32_t);
}

namespace {
const char* override_key = nullptr;
bool override_enabled = false;
std::string expected_key;
std::vector<unsigned char> packet;
int calls = 0, failures = 0, checks = 0;
int prepare_calls = 0;
int64_t prepare_error = 0;
uint32_t page_size = 4096;
uint32_t saved_uid = 1000, saved_gid = 1000;
bool respond = false;
uint64_t response = 0;
std::vector<std::array<unsigned char, 18>> frames;
bool bounds_red = false;

void check(bool ok, const char* detail) {
    ++checks;
    if (!ok) { ++failures; std::fprintf(stderr, "FAIL: %s\n", detail); }
}

void request(const char* key, bool accepted, const char* label) {
    calls = 0;
    packet.clear();
    expected_key = key ? key : "";
    const auto error = kernel_module::request_kernel_set_suid_sgid(0x12345678, 0x90abcdef);
    check(error == (accepted ? KModErr::OK : KModErr::ERR_MODULE_ROOT_KEY), label);
    check(calls == (accepted ? 1 : 0), "request transport admission");
    if (!accepted || packet.empty()) return;
    check(std::memcmp(packet.data(), key, expected_key.size()) == 0, "full key reaches packet");
    const unsigned char frame[] = {52, 0, 0x78, 0x56, 0x34, 0x12, 0xef, 0xcd, 0xab, 0x90, 0, 0, 0, 0, 0, 0, 0, 0};
    bool decoded = true;
    for (size_t i = 0; i < sizeof(frame); ++i) {
        const auto mask = packet[expected_key.size() + i];
        const auto value = packet[expected_key.size() + 18 + i];
        decoded &= static_cast<unsigned char>(value + mask) == frame[i];
    }
    check(decoded && packet.back() == 0, "request frame and terminator preserved");
}

void allocation(uint32_t uid, uint32_t gid, bool reply, uint64_t value,
                KModErr expected, const char* label) {
    saved_uid = uid;
    saved_gid = gid;
    respond = reply;
    response = value;
    calls = 0;
    frames.clear();
    uint64_t address = 0xfeed;
    check(kernel_module::unsafe_request_kernel_alloc_mem(4097, address) == expected, label);
    const uint64_t expected_address = expected == KModErr::OK ? value : 0xfeed;
    check(address == expected_address && calls == 1, "allocation consumes full saved-ID receipt");
    const unsigned char frame[] = {48, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x20, 0, 0, 2, 0, 0, 0};
    check(frames.size() == 1 && std::memcmp(frames[0].data(), frame, sizeof(frame)) == 0,
          "allocation frame contains aligned byte size and page count");
}

uint32_t frame_u32(size_t offset) {
    uint32_t value = 0;
    if (!frames.empty()) std::memcpy(&value, frames[0].data() + offset, sizeof(value));
    return value;
}

void allocation_bounds() {
    const auto maximum = std::numeric_limits<uint32_t>::max();
    for (const uint32_t page : {4096u, 16384u, 65536u}) {
        page_size = page;
        for (const auto size : {0u, maximum - page + 2, maximum}) {
            calls = prepare_calls = 0; uint64_t address = 0xfeed;
            check(kernel_module::unsafe_request_kernel_alloc_mem(size, address) == KModErr::ERR_MODULE_PARAM,
                  "invalid allocation size returns parameter error");
            check(calls == 0 && prepare_calls == 0 && address == 0xfeed,
                  "invalid allocation preserves output before preparation and transport");
            check(kernel_module::request_kernel_alloc_mem(size, address) == KModErr::ERR_MODULE_PARAM &&
                      calls == 0 && prepare_calls == 0 && address == 0xfeed,
                  "invalid guarded allocation does not restore unchanged saved IDs through transport");
        }
        for (const uint32_t size : {1u, page, page + 1, maximum - page + 1}) {
            saved_uid = saved_gid = 1000; respond = true; response = UINT64_C(0xffff000012340000);
            calls = prepare_calls = 0; frames.clear(); uint64_t address = 0xfeed;
            const auto aligned = static_cast<uint32_t>((uint64_t(size) + page - 1) & ~(uint64_t(page) - 1));
            check(kernel_module::unsafe_request_kernel_alloc_mem(size, address) == KModErr::OK,
                  "valid page and maximum-alignable allocation succeeds");
            check(calls == 1 && prepare_calls == 1 && address == response &&
                      frame_u32(10) == aligned && frame_u32(14) == aligned / page,
                  "valid allocation retains aligned size and page-count frame");
        }
    }
    for (const uint32_t page : {0u, 3u, 6144u, maximum}) {
        page_size = page; calls = prepare_calls = 0; uint64_t address = 0xfeed;
        check(kernel_module::unsafe_request_kernel_alloc_mem(4096, address) == KModErr::ERR_MODULE_PARAM,
              "invalid page size returns allocation parameter error");
        check(calls == 0 && prepare_calls == 0 && address == 0xfeed,
              "invalid page size preserves allocation output without transport");
        check(kernel_module::request_kernel_alloc_mem(4096, address) == KModErr::ERR_MODULE_PARAM &&
                  calls == 0 && prepare_calls == 0 && address == 0xfeed,
              "invalid guarded allocation page size is local");
    }
    for (const auto params : {std::pair<uint32_t, uint32_t>{4096, maximum}, {16384, maximum},
                              {65536, maximum}, {0, 4096}, {3, 4096}, {6144, 4096}}) {
        page_size = params.first; calls = prepare_calls = 0; uint64_t reply = 0xfeed;
        check(kernel_module::unsafe_request_kernel_exec_mem(UINT64_C(0xffff000000001000), params.second, reply)
                  == static_cast<KModErr>(-19999), "legacy execution keeps its existing invalid-size error");
        check(calls == 0 && prepare_calls == 0 && reply == 0xfeed,
              "legacy execution invalid size remains local and preserves reply");
    }
    page_size = 4096;
}

void memory_bounds() {
    constexpr uint64_t maximum = std::numeric_limits<uint64_t>::max();
    const auto user_maximum = std::numeric_limits<uintptr_t>::max();
    std::array<uint8_t, 4> bytes{0x11, 0x22, 0x33, 0x44};
    const auto original = bytes;
    struct Case { uint64_t kernel; void* buffer; uint32_t size; bool unsafe_baseline; };
    const Case cases[] = {{0, bytes.data(), 1, false}, {1, bytes.data(), 0, false},
        {1, nullptr, 1, true}, {maximum, bytes.data(), 2, false},
        {maximum - 1, bytes.data(), 3, false},
        {1, reinterpret_cast<void*>(user_maximum), 2, true},
        {1, reinterpret_cast<void*>(user_maximum - 1), 3, true}};
    for (const auto& item : cases) {
        calls = prepare_calls = 0;
        // Baseline READ reaches no user store when preparation fails. A WRITE
        // dereferences its source before that seam, so its bad user pointers are
        // deliberately skipped only in the explicit red run.
        prepare_error = static_cast<int64_t>(KModErr::ERR_MODULE_CRED_DANGER);
        check(kernel_module::unsafe_request_kernel_read_mem(item.kernel, item.buffer, item.size)
                  == KModErr::ERR_MODULE_PARAM, "invalid legacy read rejects either-end range");
        check(calls == 0 && prepare_calls == 0 && bytes == original,
              "invalid legacy read preserves output before preparation and transport");
        calls = prepare_calls = 0;
        check(kernel_module::maplesu_legacy_request_kernel_read_mem(item.kernel, item.buffer, item.size)
                  == KModErr::ERR_MODULE_PARAM && calls == 0 && prepare_calls == 0 && bytes == original,
              "invalid guarded legacy read does not send a restore packet");
        if (bounds_red && item.unsafe_baseline) continue;
        calls = prepare_calls = 0;
        check(kernel_module::unsafe_request_kernel_write_mem(item.kernel, item.buffer, item.size)
                  == KModErr::ERR_MODULE_PARAM, "invalid legacy write rejects either-end range");
        check(calls == 0 && prepare_calls == 0 && bytes == original,
              "invalid legacy write is local without preparation or transport");
        check(kernel_module::request_kernel_write_mem(item.kernel, item.buffer, item.size)
                  == KModErr::ERR_MODULE_PARAM && calls == 0 && prepare_calls == 0 && bytes == original,
              "invalid public legacy write is local");
    }
    prepare_error = 0; respond = true; response = 0xab;
    for (const auto start : {uint64_t(1), maximum - 3}) {
        calls = prepare_calls = 0; frames.clear(); bytes = original;
        check(kernel_module::unsafe_request_kernel_read_mem(start, bytes.data(), bytes.size()) == KModErr::OK,
              "last valid kernel byte is readable");
        check(calls == 4 && prepare_calls == 4 && bytes == std::array<uint8_t, 4>{0xab, 0xab, 0xab, 0xab},
              "valid legacy read retains bytewise transport and full output");
        calls = prepare_calls = 0; frames.clear(); bytes = original;
        check(kernel_module::unsafe_request_kernel_write_mem(start, bytes.data(), bytes.size()) == KModErr::OK,
              "last valid kernel byte is writable");
        check(calls == 4 && prepare_calls == 4 && frames.size() == bytes.size(),
              "valid legacy write retains bytewise transport");
        for (size_t i = 0; i < frames.size(); ++i) {
            uint64_t address = 0; std::memcpy(&address, frames[i].data() + 2, sizeof(address));
            check(frames[i][0] == 49 && frames[i][1] == bytes[i] && address == start + i,
                  "valid legacy write retains address and byte content");
        }
    }
}
}

namespace kernel_module { uint32_t get_page_size() { return page_size; } }
extern "C" int64_t maplesu_prepare_kernel_request() noexcept { ++prepare_calls; return prepare_error; }

extern "C" const char* __wrap___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW() {
    return override_enabled ? override_key : __real___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
}

extern "C" int __wrap_getresuid(uid_t* real, uid_t* effective, uid_t* saved) {
    *real = *effective = 1000;
    *saved = saved_uid;
    return 0;
}

extern "C" int __wrap_getresgid(gid_t* real, gid_t* effective, gid_t* saved) {
    *real = *effective = 1000;
    *saved = saved_gid;
    return 0;
}

extern "C" long __wrap_syscall(long number, ...) {
    check(number == 221, "kernel request syscall number");
    va_list args;
    va_start(args, number);
    const auto* bytes = va_arg(args, const unsigned char*);
    packet.assign(bytes, bytes + expected_key.size() + 37);
    va_end(args);
    ++calls;
    std::array<unsigned char, 18> frame{};
    for (size_t i = 0; i < frame.size(); ++i)
        frame[i] = packet[expected_key.size() + i] + packet[expected_key.size() + 18 + i];
    frames.push_back(frame);
    if (frame[0] == 52) {
        std::memcpy(&saved_uid, frame.data() + 2, sizeof(saved_uid));
        std::memcpy(&saved_gid, frame.data() + 6, sizeof(saved_gid));
    } else if (respond) {
        saved_uid = static_cast<uint32_t>(response);
        saved_gid = static_cast<uint32_t>(response >> 32);
    }
    return -1;  // The legacy protocol consumes saved IDs, not the syscall return.
}

int main() {
    check(kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::LegacyKey) == KModErr::OK,
          "legacy protocol is selected explicitly; cold threads default to owner");
    bounds_red = std::getenv("MAPLESU_BOUNDS_RED") != nullptr;
    for (size_t length : {size_t(0), size_t(1), size_t(15), size_t(16), size_t(17), size_t(48), size_t(1023)}) {
        const std::string key(length, 'K');
        __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(key.c_str());
        check(std::strcmp(__real___get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(), key.c_str()) == 0,
              "production identity preserves complete key");
        const auto label = "key length " + std::to_string(length);
        request(key.c_str(), length >= 16, label.c_str());
    }
    override_enabled = true;
    override_key = nullptr;
    request(nullptr, false, "null getter");
    for (size_t length : {size_t(1024), size_t(2048)}) {
        const std::string key(length, 'L');
        override_key = key.c_str();
        request(key.c_str(), false, "transport rejects unterminated key within 1024");
    }
    prepare_error = static_cast<int64_t>(KModErr::ERR_MODULE_CRED_DANGER);
    calls = 0;
    check(kernel_module::request_kernel_set_suid_sgid(0, 0) == KModErr::ERR_MODULE_CRED_DANGER && calls == 0,
          "request preparation error remains first");
    prepare_error = 0;
    override_enabled = false;
    expected_key.assign(48, 'K');
    __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(expected_key.c_str());

    // Independent contract: formal m023 0x144..0x14c reports NO_BOOT only
    // when both saved IDs are unchanged. A successful allocation is the reply.
    allocation(1000, 1000, true, 0xffff000012345000ull, KModErr::OK, "both saved IDs changed: allocation succeeds");
    allocation(1000, 0xffff0000, true, 0xffff000012345000ull,
               KModErr::OK, "only saved UID changed: allocation succeeds");
    allocation(0x12345000, 1000, true, 0xffff000012345000ull,
               KModErr::OK, "only saved GID changed: allocation succeeds");
    allocation(1000, 1000, false, 0, KModErr::ERR_MODULE_NO_BOOT, "unchanged saved IDs indicate absent channel");
    allocation(1000, 1000, true, 0, KModErr::ERR_MODULE_ALLOC_MEM, "changed IDs with zero reply indicate allocation failure");
    allocation(0, 0, false, 0, KModErr::ERR_MODULE_NO_BOOT, "no response precedes zero-address error");

    saved_uid = 1234; saved_gid = 5678;
    respond = true; response = 0xffff000076543000ull;
    frames.clear(); calls = 0;
    uint64_t address = 0;
    check(kernel_module::request_kernel_alloc_mem(1, address) == KModErr::OK,
          "guarded allocation preserves success code");
    check(address == response && saved_uid == 1234 && saved_gid == 5678,
          "guard restores original saved IDs after capturing address");
    check(calls == 2 && frames[0][0] == 48 && frames[1][0] == 52,
          "guard restores via the existing channel after allocation");

    prepare_error = static_cast<int64_t>(KModErr::ERR_MODULE_CRED_DANGER);
    calls = 0; address = 0xfeed;
    check(kernel_module::unsafe_request_kernel_alloc_mem(4096, address) == KModErr::ERR_MODULE_CRED_DANGER
              && address == 0xfeed && calls == 0,
          "allocation preserves preparation error and untouched output");
    prepare_error = 0;
    override_enabled = true; override_key = nullptr;
    check(kernel_module::unsafe_request_kernel_alloc_mem(4096, address) == KModErr::ERR_MODULE_ROOT_KEY
              && address == 0xfeed && calls == 0,
          "allocation preserves key error and untouched output");
    override_enabled = false;

    for (const uint64_t reply : {uint64_t(0xffffffffffffb1e1ull), uint64_t(0xfffeffffb1e1ull),
                                 uint64_t(0x1234567887654321ull)}) {
        respond = true; response = reply; calls = 0;
        uint64_t returned = 0xfeed;
        const auto expected = reply == 0x1234567887654321ull ? KModErr::OK : static_cast<KModErr>(-19999);
        check(kernel_module::unsafe_request_kernel_exec_mem(0xffff000000001000ull, 4097, returned) == expected
                  && returned == (expected == KModErr::OK ? reply : 0xfeed) && calls == 1,
              "execution decodes full reply and both historical failure representations");
    }
    for (const auto params : {std::pair<uint64_t, uint32_t>{0, 4096}, {0x1000, 0}}) {
        calls = 0; uint64_t returned = 0xfeed;
        check(kernel_module::unsafe_request_kernel_exec_mem(params.first, params.second, returned)
                  == static_cast<KModErr>(-19999) && calls == 0 && returned == 0xfeed,
              "invalid execution inputs do not send a packet");
    }
    allocation_bounds();
    memory_bounds();
    std::printf("argument-bounds: invalid write-user-pointer cases %s\n", bounds_red ? "skipped in red" : "executed");
    std::printf("kernel-request-key: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
