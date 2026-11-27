#include "module_err_def.h"
#include <algorithm>
#include "aarch64_asm_helper.h"
#include "module_base_kernel_mem.h"
#include "module_base_kernel_symbol_kaddr.h"
#include "kernel_read_metadata.h"
#include <limits>
#include <new>
#include "maplesu_request_private.h"
#include "maplesu_request_identity.h"
#include <cstring>
#include <unistd.h>
extern "C" const char* __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();

namespace kernel_module {
void maplesu_legacy_asm_func_start(asmjit::a64::Assembler*);
void maplesu_legacy_asm_func_end(asmjit::a64::Assembler*, asmjit::a64::GpX);
KModErr execute_kernel_asm_func_with_buf(const uint8_t*, uint32_t, uint64_t&);
KModErr get_append_package_run_fake_proc_path_addr(uint64_t&);
}

namespace {
using namespace asmjit;
using namespace asmjit::a64;
using namespace kernel_module;
constexpr uint32_t batch_size = 256;
constexpr uint64_t bootstrap_fault = uint64_t{1} << 63;
struct ReadSymbols {
    RequestIdentity identity;
    bool resolving = false;
    uint64_t metadata_begin = 0;
    uint64_t nofault = 0;
    uint64_t copy_to_user = 0;
};
thread_local ReadSymbols symbols;

KModErr execute(aarch64_asm_ctx& context, uint64_t& result) {
    const auto code = aarch64_asm_to_bytes(context.assembler());
    if (context.has_error() || code.empty()) return KModErr::ERR_MODULE_ASM;
    return execute_kernel_asm_func_with_buf(code.data(), code.size(), result);
}

// Only kallsyms' immutable append metadata uses this bootstrap. Ordinary reads
// always use the kernel nofault routine, including after symbols are resolved.
KModErr read_symbol_metadata(uint64_t source, uint8_t* destination, uint32_t size) {
    if (!symbols.identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (!maplesu_kernel_read::metadata_read_allowed(symbols.metadata_begin, source, size))
        return KModErr::ERR_MODULE_NOT_KADDR;
    for (uint32_t done = 0; done < size;) {
        const auto count = std::min(uint32_t{7}, size - done);
        auto context = init_aarch64_asm();
        auto a = context.assembler();
        const auto failed = a->newLabel(), restore = a->newLabel();
        maplesu_legacy_asm_func_start(a);
        aarch64_asm_mrs_daif(a, x14);
        aarch64_asm_mrs_par_el1(a, x15);
        aarch64_asm_msr_daifset(a, 2);
        a->mov(x0, xzr);
        a->mov(x9, Imm(source + done));
        for (uint32_t i = 0; i < count; ++i) {
            aarch64_asm_at_s1e1r(a, x9);
            aarch64_asm_isb(a);
            aarch64_asm_mrs_par_el1(a, x12);
            a->tbnz(x12, Imm(0), failed);
            a->ldrb(w10, ptr(x9));
            if (i) a->lsl(x10, x10, Imm(i * 8));
            a->orr(x0, x0, x10);
            if (i + 1 < count) a->add(x9, x9, Imm(1));
        }
        a->b(restore);
        a->bind(failed);
        a->mov(x0, Imm(bootstrap_fault));
        a->bind(restore);
        a->msr(Predicate::SysReg::kPAR_EL1, x15);
        aarch64_asm_msr_daif(a, x14);
        maplesu_legacy_asm_func_end(a, x0);
        uint64_t result = 0;
        if (!symbols.identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
        const auto error = execute(context, result);
        if (is_failed(error)) return error;
        if (!symbols.identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
        if (result & bootstrap_fault) return KModErr::ERR_MODULE_NOT_KADDR;
        for (uint32_t i = 0; i < count; ++i)
            destination[done + i] = static_cast<uint8_t>(result >> (i * 8));
        done += count;
    }
    return KModErr::OK;
}

template<size_t N>
KModErr find_symbol(const char* const (&names)[N], uint64_t& output) {
    for (const auto name : names) {
        SymbolHit hit{};
        const auto error = kallsyms_lookup_name(name, hit, SymbolMatchMode::Exact);
        if (is_ok(error) && hit.addr >= 0xffff000000000000ULL) {
            output = hit.addr;
            return KModErr::OK;
        }
        if (is_failed(error) && error != KModErr::ERR_MODULE_SYMBOL_NOT_EXIST)
            return error;
    }
    return KModErr::ERR_MODULE_SYMBOL_NOT_EXIST;
}

KModErr resolve_symbols(const RequestIdentity& identity) {
    if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (!symbols.identity.matches()) symbols = {};
    if (symbols.nofault && symbols.copy_to_user) return KModErr::OK;
    symbols.identity = identity;
    uint64_t path_address = 0;
    const auto anchor_error = get_append_package_run_fake_proc_path_addr(path_address);
    if (is_failed(anchor_error)) return anchor_error;
    if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (path_address < 0xffff000000000008ULL)
        return KModErr::ERR_MODULE_INTERNAL_APPEND;
    symbols.metadata_begin = path_address - 8;
    struct ResolvingGuard {
        ResolvingGuard() { symbols.resolving = true; }
        ~ResolvingGuard() { symbols.resolving = false; }
    } guard;
    constexpr const char* nofault_names[] = {
        "copy_from_kernel_nofault", "probe_kernel_read", "__probe_kernel_read"
    };
    constexpr const char* user_names[] = {
        "copy_to_user_nofault"
    };
    uint64_t nofault = 0, copy_to_user = 0;
    auto error = find_symbol(nofault_names, nofault);
    if (is_failed(error)) return error;
    if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    error = find_symbol(user_names, copy_to_user);
    if (is_failed(error)) return error;
    if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    symbols.nofault = nofault;
    symbols.copy_to_user = copy_to_user;
    return KModErr::OK;
}

KModErr read_batch(uint64_t source, uint8_t* destination, uint32_t size) {
    auto context = init_aarch64_asm();
    auto a = context.assembler();
    const auto failed = a->newLabel(), restore = a->newLabel();
    maplesu_legacy_asm_func_start(a);
    // Native calls may destroy x0-x17 and LR; retain the executor's private
    // registers and keep the entire copied value in this kernel stack frame.
    a->stp(x11, x17, ptr(sp).pre(-16));
    a->stp(x29, x30, ptr(sp).pre(-16));
    a->sub(sp, sp, Imm(batch_size));
    a->mov(x0, sp);
    a->mov(x1, Imm(source));
    a->mov(x2, Imm(size));
    a->mov(x16, Imm(symbols.nofault));
    a->blr(x16);
    a->cbnz(x0, failed);
    a->mov(x0, Imm(reinterpret_cast<uintptr_t>(destination)));
    a->mov(x1, sp);
    a->mov(x2, Imm(size));
    a->mov(x16, Imm(symbols.copy_to_user));
    a->blr(x16);
    a->cbnz(x0, failed);
    a->mov(x0, xzr);
    a->b(restore);
    a->bind(failed);
    a->mov(x0, Imm(static_cast<uint64_t>(to_num(KModErr::ERR_MODULE_NOT_KADDR))));
    a->bind(restore);
    a->add(sp, sp, Imm(batch_size));
    a->ldp(x29, x30, ptr(sp).post(16));
    a->ldp(x11, x17, ptr(sp).post(16));
    maplesu_legacy_asm_func_end(a, x0);
    uint64_t result = 0;
    const auto error = execute(context, result);
    if (is_failed(error)) return error;
    return static_cast<KModErr>(static_cast<ssize_t>(result));
}
}

namespace kernel_module {
KModErr maplesu_kernel_metadata_v2(maplesu_metadata_v2& metadata,
                                 uint64_t& descriptor_address, bool& supported) {
    struct Cache {
        bool valid = false;
        pid_t pid = 0;
        uint64_t auth_state = 0;
        char key[49] = {};
        uint64_t address = 0;
        maplesu_metadata_v2 metadata{};
    };
    static thread_local Cache cache;
    metadata = {};
    descriptor_address = 0;
    supported = false;
    if (maplesu_request_owner_mode()) return KModErr::ERR_MODULE_TEST_CHANNEL;
    // Keep the identity that negotiated this descriptor, rather than retaining
    // a pointer into the module's mutable Key storage across the READ.
    const char* key = __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
    if (!key || strnlen(key, 49) != 48) {
        cache = {};
        return KModErr::ERR_MODULE_ROOT_KEY;
    }
    char identity[48];
    std::memcpy(identity, key, sizeof(identity));
    const auto pid = getpid();
    const auto auth_state = maplesu_request_auth_state();
    maplesu_response_v2 query{};
    bool negotiated = false;
    auto error = maplesu_request_v2_channel(query, negotiated);
    if (is_failed(error) || !negotiated) {
        cache = {};
        return error;
    }
    if (!(query.result & MAPLESU_CAP_READ)) {
        cache = {};
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    const char* negotiated_key = __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
    if (!negotiated_key || strnlen(negotiated_key, 49) != sizeof(identity) ||
        std::memcmp(identity, negotiated_key, sizeof(identity)) || pid != getpid() ||
        auth_state != maplesu_request_auth_state()) {
        cache = {};
        return KModErr::ERR_MODULE_ROOT_KEY;
    }
    if (!cache.valid || cache.pid != pid || cache.auth_state != auth_state ||
        std::memcmp(cache.key, identity, sizeof(identity)) ||
        cache.address != query.metadata_address || cache.metadata.capabilities != query.result) {
        cache = {};
        maplesu_metadata_v2 candidate{};
        error = maplesu_request_v2_read(query.metadata_address, &candidate, sizeof(candidate));
        if (is_failed(error)) return error;
        if (!maplesu_kernel_read::valid_metadata(candidate, query.metadata_address, query.result))
            return KModErr::ERR_MODULE_INTERNAL_APPEND;
        const char* after = __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
        if (!after || strnlen(after, 49) != sizeof(identity) ||
            std::memcmp(identity, after, sizeof(identity)) || pid != getpid() ||
            auth_state != maplesu_request_auth_state())
            return KModErr::ERR_MODULE_ROOT_KEY;
        cache.pid = pid;
        cache.auth_state = auth_state;
        std::memcpy(cache.key, identity, sizeof(identity));
        cache.address = query.metadata_address;
        cache.metadata = candidate;
        cache.valid = true;
    }
    if (auth_state != maplesu_request_auth_state()) {
        cache = {};
        return KModErr::ERR_MODULE_ROOT_KEY;
    }
    metadata = cache.metadata;
    descriptor_address = cache.address;
    supported = true;
    return KModErr::OK;
}
KModErr maplesu_kernel_legacy_read(uint64_t source, void* destination, uint32_t size) {
    RequestIdentity identity;
    if (!identity.capture()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (!maplesu_request_auth_legacy(identity.auth_state)) return KModErr::ERR_MODULE_TEST_CHANNEL;
    maplesu_response_v2 query{};
    bool supported = false;
    const auto channel_error = maplesu_request_v2_channel(query, supported);
    if (is_failed(channel_error)) return channel_error;
    if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (supported) {
        const auto error = maplesu_request_v2_read(source, destination, size);
        if (is_failed(error)) return error;
        return identity.matches() ? KModErr::OK : KModErr::ERR_MODULE_ROOT_KEY;
    }
    auto bytes = static_cast<uint8_t*>(destination);
    if (symbols.resolving) return read_symbol_metadata(source, bytes, size);
    const auto error = resolve_symbols(identity);
    if (is_failed(error)) return error;
    for (uint32_t done = 0; done < size;) {
        const auto count = std::min(batch_size, size - done);
        if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
        const auto result = read_batch(source + done, bytes + done, count);
        if (is_failed(result)) return result;
        if (!identity.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
        done += count;
    }
    return KModErr::OK;
}
} // namespace kernel_module
