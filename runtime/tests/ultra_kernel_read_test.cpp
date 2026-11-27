#include "module_err_def.h"
#include <algorithm>
#include "aarch64_asm_helper.h"
#include "module_base_kernel_mem.h"
#include "module_base_kernel_symbol_kaddr.h"
#include <array>
#include <cassert>
#include <cstring>
#include <sys/prctl.h>
#include "module_base_kernel_request.h"
#include "maplesu_request_private.h"
#include "../ultra/authorization/kernel_read_metadata.h"

extern "C" KModErr __wrap__ZN13kernel_module23request_kernel_read_memEmPvj(uint64_t, void*, uint32_t);

namespace {
constexpr uint64_t source = 0xffff800000500ff8ULL;
constexpr uint64_t metadata = 0xffff800000700010ULL;
constexpr uint64_t nofault = 0xffff800000900000ULL;
constexpr uint64_t copy_user = 0xffff800000901000ULL;
std::vector<uint8_t> staged;
unsigned restores, secure_restores;
int fixture_securebits;
std::array<uint8_t, 600> output;
std::string mode;
std::string credential(48, 'K');
bool resolving;
int bootstrap_calls, read_calls, lookups;
uint32_t bootstrap_offset, bootstrap_remaining;
uint8_t pattern(size_t index) { return static_cast<uint8_t>(index * 37 + 11); }
}

extern "C" const char* __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW() {
    return credential.c_str();
}
namespace kernel_module {
uint64_t maplesu_request_auth_state() { return 4; }
bool maplesu_request_owner_binding_matches(const RequestOwnerBinding&) { assert(false); return false; }
KModErr maplesu_request_owner_channel(maplesu_metadata_owner_v3&, uint64_t&, RequestOwnerBinding&) {
    assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL;
}
KModErr maplesu_request_owner_read(uint64_t, void*, uint32_t, const RequestOwnerBinding&) {
    assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL;
}
KModErr maplesu_request_v2_channel(maplesu_response_v2& query, bool& supported) {
    query = {}; supported = false; return KModErr::OK;
}
KModErr maplesu_request_v2_read(uint64_t, void*, uint32_t) {
    assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL;
}
KModErr get_append_package_run_fake_proc_path_addr(uint64_t& output) {
    output = mode == "bad-anchor" ? 0 : metadata + 8;
    return KModErr::OK;
}
KModErr kallsyms_lookup_name(const char* name, SymbolHit& out, SymbolMatchMode) {
    ++lookups;
    if (lookups == 1) {
        resolving = true;
        uint64_t value = 0;
        const auto before = bootstrap_calls;
        assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata - 1, &value, 1)));
        assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata + 8, &value, 1)));
        assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata + 7, &value, 2)));
        assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata + 0x29, &value, 2)));
        assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata + 0x33, &value, 2)));
        assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata + 0x1000, &value, 4)));
        assert(before == bootstrap_calls);
        bootstrap_offset = 0;
        bootstrap_remaining = 8;
        const auto error = __wrap__ZN13kernel_module23request_kernel_read_memEmPvj(metadata, &value, 8);
        if (is_failed(error)) { resolving = false; return error; }
        assert(value == 0x1716151413121110ULL);
        for (const auto request : {std::pair<uint32_t, uint32_t>{0x30, 4}, {0, 4}, {0x2a, 6}, {4, 4}}) {
            bootstrap_offset = request.first;
            bootstrap_remaining = request.second;
            value = 0;
            assert(is_ok(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(
                metadata + request.first, &value, request.second)));
            uint64_t expected = 0;
            for (uint32_t i = 0; i < request.second; ++i)
                expected |= uint64_t{0x10 + request.first + i} << (i * 8);
            assert(value == expected);
        }
        resolving = false;
    }
    const bool primary = std::strcmp(name, "copy_from_kernel_nofault") == 0;
    const bool older = std::strcmp(name, "probe_kernel_read") == 0;
    if (primary || older || std::strcmp(name, "__probe_kernel_read") == 0) {
        if (mode == "missing" || (primary && mode == "older")) return KModErr::ERR_MODULE_SYMBOL_NOT_EXIST;
        out.addr = nofault;
    } else {
        assert(std::strcmp(name, "copy_to_user_nofault") == 0);
        if (mode == "raw-only") return KModErr::ERR_MODULE_SYMBOL_NOT_EXIST;
        out.addr = copy_user;
    }
    return KModErr::OK;
}
KModErr request_kernel_alloc_mem(uint32_t size,uint64_t& address) {
    assert(size); address=source+0x10000; return KModErr::OK;
}
KModErr unsafe_request_kernel_write_mem(uint64_t address,const void* bytes,uint32_t size) {
    assert(address==source+0x10000);
    const auto* data=static_cast<const uint8_t*>(bytes); staged.assign(data,data+size); return KModErr::OK;
}
KModErr request_kernel_set_suid_sgid(uint32_t,uint32_t) { ++restores; return KModErr::OK; }
KModErr maplesu_request_owner_execute(const uint8_t*,uint32_t,uint64_t&) { assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL; }
KModErr maplesu_request_key_execute(const uint8_t*,uint32_t,uint64_t&,bool&) { assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL; }
KModErr maplesu_request_key_credential_begin() { assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL; }
KModErr maplesu_request_key_credential_end(KModErr) { assert(false); return KModErr::ERR_MODULE_TEST_CHANNEL; }
CleanupReceiptScope::CleanupReceiptScope() noexcept {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept {}
KModErr unsafe_request_kernel_exec_mem(uint64_t address,uint32_t size,uint64_t& reply) {
    assert(address==source+0x10000 && size==staged.size());
    const auto* code=staged.data();
    uint64_t result=0;
    const int index = resolving ? bootstrap_calls++ : read_calls++ % 3;
    const auto count = std::min(uint32_t{7}, bootstrap_remaining);
    std::printf("capture=%s,%d,%s,%u,%u,", resolving ? "bootstrap" : "read", index, mode.c_str(),
                resolving ? bootstrap_offset : 0, resolving ? count : 0);
    for (uint32_t i = 0; i < size; ++i) std::printf("%02x", code[i]);
    std::printf("\n");
    result = 0;
    if (resolving) {
        if (mode == "bootstrap-fault") result = 1ULL << 63;
        else {
            for (uint32_t i = 0; i < count; ++i)
                result |= uint64_t{0x10 + bootstrap_offset + i} << (i * 8);
            bootstrap_offset += count;
            bootstrap_remaining -= count;
        }
    } else {
        if (mode == "execute-error") return KModErr::ERR_MODULE_ASM;
        if (mode == "fault" || mode == "cross-page-fault" || mode == "copy-fault") {
            result = static_cast<uint64_t>(KModErr::ERR_MODULE_NOT_KADDR);
            if (mode == "copy-fault") output[0] = pattern(0);
        }
        else {
            const size_t begin = static_cast<size_t>(index) * 256;
            const size_t end = std::min(begin + 256, output.size());
            for (size_t i = begin; i < end; ++i) output[i] = pattern(i);
        }
    }
    // Model only the legacy syscall receipt. Actual m025 decodes it, checks
    // PR_GET_SECUREBITS, restores saved credentials, and publishes the result.
    reply=(result&UINT64_C(0x0000ffffffffffff))<<8;
    fixture_securebits=static_cast<int>(result>>48);
    return KModErr::OK;
}
}

extern "C" int prctl(int option,...) noexcept {
    assert(option==PR_GET_SECUREBITS); return fixture_securebits;
}
extern "C" KModErr maplesu_restore_securebits(uint32_t value) {
    ++secure_restores; fixture_securebits=static_cast<int>(value); return KModErr::OK;
}
extern "C" int64_t maplesu_release_staging(uint64_t) { assert(false); return 0; }
int main(int argc, char** argv) {
    assert(argc == 2);
    mode = argv[1];
    output.fill(0xa5);
    const auto error = __wrap__ZN13kernel_module23request_kernel_read_memEmPvj(source, output.data(), output.size());
    const bool success = mode == "good" || mode == "older";
    assert(success ? is_ok(error) : is_failed(error));
    assert(bootstrap_calls == (mode == "bad-anchor" ? 0 : mode == "bootstrap-fault" ? 1 : 6));
    assert(read_calls == (success ? 3 : mode == "missing" || mode == "raw-only" || mode == "bootstrap-fault" || mode == "bad-anchor" ? 0 : 1));
    for (size_t i = 0; i < output.size(); ++i)
        assert(output[i] == (success || (mode == "copy-fault" && i == 0) ? pattern(i) : 0xa5));
    if (success) {
        const auto resolved = lookups;
        output.fill(0xa5);
        assert(is_ok(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(source, output.data(), output.size())));
        assert(lookups == resolved && bootstrap_calls == 6 && read_calls == 6);
        for (size_t i = 0; i < output.size(); ++i) assert(output[i] == pattern(i));
    }
    if (mode == "missing" || mode == "raw-only") {
        assert(error == KModErr::ERR_MODULE_SYMBOL_NOT_EXIST);
        const auto failed_lookups = lookups;
        const auto previous_mode = mode;
        mode = "good";
        assert(is_ok(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(source, output.data(), output.size())));
        assert(lookups == failed_lookups + 2 && read_calls == 3 && bootstrap_calls == 6);
        for (size_t i = 0; i < output.size(); ++i) assert(output[i] == pattern(i));
        mode = previous_mode;
    }
    const int before = bootstrap_calls + read_calls;
    assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(source, nullptr, 1)));
    assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(source, output.data(), 0)));
    assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(0x1000, output.data(), 1)));
    assert(is_failed(__wrap__ZN13kernel_module23request_kernel_read_memEmPvj(UINT64_MAX - 1, output.data(), 4)));
    assert(before == bootstrap_calls + read_calls);
    assert(restores==static_cast<unsigned>(bootstrap_calls+read_calls));
    assert(secure_restores==restores && fixture_securebits==0);
    std::printf("PASS mode=%s bootstrap=%d batches=%d lookups=%d\n", mode.c_str(), bootstrap_calls, read_calls, lookups);
}
