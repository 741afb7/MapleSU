#pragma once

#include "maplesu_request_identity.h"
#include "../../../../ultra/authorization/kernel_read_metadata.h"
#include "../../../../ultra/authorization/legacy_hook_private.h"

namespace {
struct LegacyHookAllocation { uint64_t handle; uint32_t bytes; };
struct LegacyHookPublication { uint64_t id; std::vector<uint64_t> addresses; };
struct LegacyHookManagement {
    RequestIdentity identity;
    uint64_t session = 0;
    maplesu_host_instance host{};
    bool uncertain = false;
    RequestManagementReply reply{};
    std::map<uint64_t, LegacyHookAllocation> allocations;
    std::vector<LegacyHookPublication> publications;
};
thread_local LegacyHookManagement legacy_hook_management;

void legacy_hook_after_fork() {
    if (legacy_hook_management.identity.process &&
        legacy_hook_management.identity.process != getpid()) legacy_hook_management = {};
}

KModErr legacy_hook_status(int32_t status) {
    if (!status) return KModErr::OK;
    errno = -status;
    if (status == -EFAULT) return KModErr::ERR_MODULE_NOT_KADDR;
    if (status == -ENOMEM) return KModErr::ERR_NO_MEM;
    if (status == -EPERM || status == -ESTALE) return KModErr::ERR_MODULE_ROOT_KEY;
    return KModErr::ERR_MODULE_TEST_CHANNEL;
}

// The public descriptor remains 48 bytes. Only this same-generation private
// tail names the real native provider; a missing bridge is an actual failure.
KModErr legacy_hook_provider(uint64_t& get, uint64_t& put) {
    maplesu_metadata_v2 metadata{};
    uint64_t descriptor = 0;
    bool supported = false;
    auto error = maplesu_kernel_metadata_v2(metadata, descriptor, supported);
    if (error != KModErr::OK) return error;
    if (!supported || descriptor > UINT64_MAX - sizeof(metadata) -
            sizeof(maplesu_legacy_hook_metadata_private)) {
        errno = EOPNOTSUPP;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    maplesu_legacy_hook_metadata_private tail{};
    error = maplesu_request_v2_read(descriptor + sizeof(metadata), &tail, sizeof(tail));
    if (error != KModErr::OK) return error;
    if (tail.magic != MAPLESU_LEGACY_HOOK_METADATA_MAGIC ||
        tail.schema != MAPLESU_LEGACY_HOOK_SCHEMA || tail.length != sizeof(tail) ||
        !maplesu_kernel_read::metadata_address(descriptor,
            tail.provider_get_displacement, 4, get) || (get & 3u) ||
        !maplesu_kernel_read::metadata_address(descriptor,
            tail.provider_put_displacement, 4, put) || (put & 3u)) {
        errno = EOPNOTSUPP;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    return KModErr::OK;
}

struct LegacyHookExchange {
    maplesu_response_owner_v3 response{};
    maplesu_host_instance host{};
};

// The native EXEC producer holds its own provider/scope lease. This second
// get/put brackets every table access and dispatch, including error exits.
KModErr legacy_hook_exchange(uint32_t command, maplesu_management_v3 operation) {
    auto& state = legacy_hook_management;
    state.reply = {};
    RequestIdentity identity;
    if (!maplesu_request_auth_legacy(maplesu_request_auth_state()) ||
        !identity.capture() || (state.session && !state.identity.matches()))
        return KModErr::ERR_MODULE_ROOT_KEY;
    uint64_t get = 0, put = 0;
    auto error = legacy_hook_provider(get, put);
    if (error != KModErr::OK) return error;
    LegacyHookExchange exchange{};
    aarch64_asm_ctx context = init_aarch64_asm();
    auto* a = context.assembler();
    if (!a) return KModErr::ERR_MODULE_ASM;
    const Label failed = a->newLabel(), release = a->newLabel(), done = a->newLabel();
    arm64_module_asm_func_start(a);
    a->sub(sp, sp, Imm(160));
    const auto* words = reinterpret_cast<const uint64_t*>(&operation);
    for (uint32_t i = 0; i < sizeof(operation) / 8; ++i) {
        aarch64_asm_mov_x(a, x10, words[i]);
        a->str(x10, ptr(sp, i * 8));
    }
    for (uint32_t i = 64; i < 160; i += 8) a->str(xzr, ptr(sp, i));
    a->add(x0, sp, Imm(128));
    a->add(x1, sp, Imm(136));
    aarch64_asm_mov_x(a, x16, get);
    a->blr(x16);
    a->sxtw(x21, w0);
    a->cbnz(x21, done);
    a->ldr(x19, ptr(sp, 128));
    a->ldr(x20, ptr(sp, 136));
    a->cbz(x19, failed);
    a->cbz(x20, failed);
    a->ldr(x10, ptr(x19, offsetof(maplesu_legacy_hook_provider, magic)));
    aarch64_asm_mov_x(a, x11, MAPLESU_LEGACY_HOOK_MAGIC);
    a->cmp(x10, x11);
    a->b(CondCode::kNE, failed);
    a->ldr(w10, ptr(x19, offsetof(maplesu_legacy_hook_provider, schema)));
    a->cmp(w10, Imm(MAPLESU_LEGACY_HOOK_SCHEMA));
    a->b(CondCode::kNE, failed);
    a->ldr(w10, ptr(x19, offsetof(maplesu_legacy_hook_provider, length)));
    a->cmp(w10, Imm(sizeof(maplesu_legacy_hook_provider)));
    a->b(CondCode::kNE, failed);
    constexpr uint64_t required = MAPLESU_CAP_SESSION | MAPLESU_CAP_ALLOC |
        MAPLESU_CAP_WRITE | MAPLESU_CAP_RELEASE | MAPLESU_CAP_CODE_RESERVE | MAPLESU_CAP_PUBLICATION;
    a->ldr(x10, ptr(x19, offsetof(maplesu_legacy_hook_provider, capabilities)));
    aarch64_asm_mov_x(a, x11, required);
    a->and_(x10, x10, x11);
    a->cmp(x10, x11);
    a->b(CondCode::kNE, failed);
    a->ldp(x10, x11, ptr(x19, offsetof(maplesu_legacy_hook_provider, host)));
    a->stp(x10, x11, ptr(sp));
    a->stp(x10, x11, ptr(sp, 112));
    a->ldr(x16, ptr(x19, offsetof(maplesu_legacy_hook_provider, dispatch_function)));
    a->cbz(x16, failed);
    a->mov(x0, xzr); // Only the producer's current authenticated EXEC scope.
    aarch64_asm_mov_x(a, x1, command);
    a->mov(x2, sp);
    a->add(x3, sp, Imm(64));
    a->blr(x16);
    aarch64_asm_mov_x(a, x0, reinterpret_cast<uintptr_t>(&exchange));
    a->add(x1, sp, Imm(64));
    KModErr emit_error = KModErr::OK;
    export_symbol::copy_to_user(a, emit_error, x0, x1, sizeof(exchange));
    if (emit_error != KModErr::OK) return emit_error;
    aarch64_asm_mov_x(a, x21, static_cast<uint64_t>(-EFAULT));
    a->cbnz(x0, release);
    a->mov(x21, xzr);
    a->b(release);
    a->bind(failed);
    aarch64_asm_mov_x(a, x21, static_cast<uint64_t>(-EPROTO));
    a->bind(release);
    a->ldr(x0, ptr(sp, 136));
    aarch64_asm_mov_x(a, x16, put);
    a->blr(x16);
    a->bind(done);
    a->add(sp, sp, Imm(160));
    arm64_module_asm_func_end(a, x21);
    if (context.has_error()) return KModErr::ERR_MODULE_ASM;
    uint64_t result = 0;
    error = execute_kernel_asm_func(aarch64_asm_to_bytes(a), result);
    if (error != KModErr::OK || result) {
        state.uncertain = true;
        return error != KModErr::OK ? error : legacy_hook_status(static_cast<int32_t>(result));
    }
    const auto& response = exchange.response;
    if (response.completion_magic != MAPLESU_RESPONSE_MAGIC ||
        response.version != MAPLESU_OWNER_PROTOCOL_VERSION || response.length != sizeof(response) ||
        response.command != command || response.request_id || response.metadata_version ||
        response.status > 0 || response.status < -4095 || !(exchange.host.low | exchange.host.high) ||
        (state.session && (state.host.low != exchange.host.low || state.host.high != exchange.host.high))) {
        state.uncertain = true;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    state.reply = {response.status, response.metadata_length, response.result,
        response.metadata_address, command, true};
    state.host = exchange.host;
    if (!identity.matches()) {
        state.uncertain = true;
        return KModErr::ERR_MODULE_ROOT_KEY;
    }
    if (response.metadata_length & MAPLESU_EFFECT_SESSION_CLOSED) state.uncertain = true;
    error = legacy_hook_status(response.status);
    if (error != KModErr::OK) return error;
    const auto effects = response.metadata_length;
    bool valid = false;
    switch (command) {
    case MAPLESU_REQUEST_SESSION_OPEN:
        valid = response.result && !response.metadata_address && !effects;
        break;
    case MAPLESU_REQUEST_SESSION_CLOSE:
        valid = !response.result && !response.metadata_address &&
            (effects & MAPLESU_EFFECT_SESSION_CLOSED) && !(effects & MAPLESU_EFFECT_QUARANTINED);
        break;
    case MAPLESU_REQUEST_ALLOC:
        valid = response.result >= UINT64_C(0xffff000000000000) && response.metadata_address &&
            response.result <= UINT64_MAX - operation.length && effects == MAPLESU_EFFECT_ALLOCATED;
        break;
    case MAPLESU_REQUEST_WRITE:
        valid = !response.metadata_address && response.result == operation.length && effects == MAPLESU_EFFECT_WRITTEN;
        break;
    case MAPLESU_REQUEST_RELEASE:
        valid = !response.result && !response.metadata_address && effects == MAPLESU_EFFECT_RELEASED;
        break;
    case MAPLESU_REQUEST_PUBLICATION_PREPARE:
        valid = response.result && response.metadata_address >= UINT64_C(0xffff000000000000) &&
            !(response.metadata_address & 7u) && effects == MAPLESU_EFFECT_PUBLICATION_PREPARED;
        break;
    case MAPLESU_REQUEST_PUBLICATION_PUBLISH:
        valid = response.metadata_address == operation.handle &&
            ((response.result == 0 && effects == (MAPLESU_EFFECT_PUBLICATION_PUBLISHED | MAPLESU_EFFECT_PUBLICATION_RETAINED)) ||
             (response.result == 4 && effects == (MAPLESU_EFFECT_PUBLICATION_PUBLISHED | MAPLESU_EFFECT_PUBLICATION_RETAINED |
                 MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN)));
        break;
    case MAPLESU_REQUEST_PUBLICATION_DISABLE:
        valid = response.metadata_address == operation.handle &&
            ((!response.result && (effects == MAPLESU_EFFECT_RELEASED || effects ==
                (MAPLESU_EFFECT_PUBLICATION_RETAINED | MAPLESU_EFFECT_GATE_PINNED))) ||
             (response.result == 4 && effects == (MAPLESU_EFFECT_PUBLICATION_RETAINED | MAPLESU_EFFECT_GATE_PINNED |
                MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN)));
        break;
    case MAPLESU_REQUEST_PUBLICATION_RETIRE: {
        const uint32_t optional = MAPLESU_EFFECT_REFERENCE_DROPPED | MAPLESU_EFFECT_SHARED_RETAINED;
        valid = response.metadata_address == operation.handle && (effects & MAPLESU_EFFECT_RELEASED) &&
            !(effects & ~(MAPLESU_EFFECT_RELEASED | optional | MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN)) &&
            (!(effects & MAPLESU_EFFECT_SHARED_RETAINED) || (effects & MAPLESU_EFFECT_REFERENCE_DROPPED)) &&
            ((!response.result && !(effects & (MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN))) ||
             (response.result == 4 && (effects & (MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN)) ==
                (MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN)));
        break;
    }
    case MAPLESU_REQUEST_SHARED_BOOL_CREATE:
        valid = response.result >= UINT64_C(0xffff000000000000) && response.metadata_address && effects == MAPLESU_EFFECT_ALLOCATED;
        break;
    case MAPLESU_REQUEST_SHARED_BOOL_STORE:
        valid = response.metadata_address == operation.handle && response.result == 1 && effects == MAPLESU_EFFECT_WRITTEN;
        break;
    case MAPLESU_REQUEST_SHARED_BOOL_RELEASE:
        valid = response.metadata_address == operation.handle && !response.result &&
            (effects == (MAPLESU_EFFECT_REFERENCE_DROPPED | MAPLESU_EFFECT_RELEASED) ||
             effects == (MAPLESU_EFFECT_REFERENCE_DROPPED | MAPLESU_EFFECT_SHARED_RETAINED));
        break;
    }
    if (!valid) {
        state.reply = {};
        state.uncertain = true;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    return KModErr::OK;
}

KModErr legacy_hook_session() {
    legacy_hook_after_fork();
    auto& state = legacy_hook_management;
    if (state.uncertain) return KModErr::ERR_MODULE_TEST_CHANNEL;
    if (state.session) return state.identity.matches() ? KModErr::OK : KModErr::ERR_MODULE_ROOT_KEY;
    RequestIdentity identity;
    if (!identity.capture() || !maplesu_request_auth_legacy(identity.auth_state))
        return KModErr::ERR_MODULE_ROOT_KEY;
    maplesu_management_v3 operation{};
    const auto error = legacy_hook_exchange(MAPLESU_REQUEST_SESSION_OPEN, operation);
    if (error != KModErr::OK) return error;
    state.session = state.reply.result;
    state.identity = identity;
    return KModErr::OK;
}

KModErr legacy_hook_manage(uint32_t command, uint64_t handle, uint64_t address,
    const void* data, uint32_t bytes, uint32_t flags = 0) {
    auto error = legacy_hook_session();
    if (error != KModErr::OK) return error;
    auto& state = legacy_hook_management;
    maplesu_management_v3 operation{};
    operation.host = state.host;
    operation.session = state.session;
    operation.handle = handle;
    operation.address = address;
    operation.data_user = reinterpret_cast<uintptr_t>(data);
    operation.length = bytes;
    operation.flags = flags;
    return legacy_hook_exchange(command, operation);
}

KModErr legacy_hook_close() {
    legacy_hook_after_fork();
    auto& state = legacy_hook_management;
    state.reply = {};
    if (!state.session && !state.uncertain) return KModErr::OK;
    maplesu_management_v3 operation{};
    operation.host = state.host;
    const auto error = legacy_hook_exchange(MAPLESU_REQUEST_SESSION_CLOSE, operation);
    if (state.reply.completed && (state.reply.effects & MAPLESU_EFFECT_SESSION_CLOSED)) {
        const auto reply = state.reply;
        state = {};
        state.reply = reply;
    }
    return error;
}

KModErr legacy_hook_reserve(uint32_t bytes, uint64_t near, uint64_t& address) {
    if (!bytes || bytes > MAPLESU_MANAGEMENT_MAX_BYTES) return KModErr::ERR_MODULE_PARAM;
    auto error = legacy_hook_manage(MAPLESU_REQUEST_ALLOC, 0, near, nullptr, bytes, MAPLESU_MANAGEMENT_ALLOC_CODE);
    if (error != KModErr::OK) return error;
    auto& state = legacy_hook_management;
    const auto reply = state.reply;
    try { state.allocations.emplace(reply.result, LegacyHookAllocation{reply.handle, bytes}); }
    catch (...) { (void)legacy_hook_close(); return KModErr::ERR_NO_MEM; }
    address = reply.result;
    return KModErr::OK;
}

KModErr legacy_hook_write(uint64_t address, const void* data, uint32_t bytes) {
    auto& allocations = legacy_hook_management.allocations;
    auto found = allocations.upper_bound(address);
    if (found == allocations.begin() || !data || !bytes) return KModErr::ERR_MODULE_PARAM;
    --found;
    const auto offset = address - found->first;
    if (offset >= found->second.bytes || bytes > found->second.bytes - offset)
        return KModErr::ERR_MODULE_PARAM;
    return legacy_hook_manage(MAPLESU_REQUEST_WRITE, found->second.handle, offset, data, bytes);
}

KModErr legacy_hook_release(uint64_t address) {
    auto& state = legacy_hook_management;
    const auto found = state.allocations.find(address);
    if (found == state.allocations.end()) return KModErr::ERR_MODULE_PARAM;
    const auto error = legacy_hook_manage(MAPLESU_REQUEST_RELEASE, found->second.handle, 0, nullptr, 0);
    if (state.reply.completed && (state.reply.effects & (MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_QUARANTINED)))
        state.allocations.erase(found);
    return error;
}

KModErr legacy_hook_prepare(const RequestPublicationMember* members, uint32_t count,
    uint32_t gate_index, uint64_t entry, uint64_t offset, uint32_t expected,
    uint64_t payload, RequestPublication& publication) {
    if (!members || !count || count > 64 || gate_index >= count) return KModErr::ERR_MODULE_PARAM;
    std::vector<maplesu_publication_member_v3> wire;
    wire.reserve(count);
    LegacyHookPublication local{};
    local.addresses.reserve(count);
    auto& state = legacy_hook_management;
    state.publications.reserve(state.publications.size() + 1);
    for (uint32_t i = 0; i < count; ++i) {
        if (members[i].role == MAPLESU_PUBLICATION_SHARED_BOOL) {
            const auto& shared = members[i].shared_bool;
            if (!shared.id || members[i].address != shared.address ||
                shared.host.low != legacy_hook_management.host.low || shared.host.high != legacy_hook_management.host.high)
                return KModErr::ERR_MODULE_PARAM;
            wire.push_back({shared.id, members[i].role, 0, shared.address});
        } else {
            const auto found = legacy_hook_management.allocations.find(members[i].address);
            if (found == legacy_hook_management.allocations.end()) return KModErr::ERR_MODULE_PARAM;
            wire.push_back({found->second.handle, members[i].role, 0, 0});
            local.addresses.push_back(members[i].address);
        }
    }
    const maplesu_publication_prepare_v3 prepare{
        reinterpret_cast<uintptr_t>(wire.data()), count, gate_index, entry, offset, expected, 0, payload, {0, 0}};
    const auto error = legacy_hook_manage(MAPLESU_REQUEST_PUBLICATION_PREPARE, 0, 0, &prepare, sizeof(prepare));
    const auto& reply = legacy_hook_management.reply;
    if (error != KModErr::OK && reply.completed && (reply.effects & (MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_QUARANTINED)))
        for (uint32_t i = 0; i < count; ++i)
            if (members[i].role != MAPLESU_PUBLICATION_SHARED_BOOL)
                legacy_hook_management.allocations.erase(members[i].address);
    if (error == KModErr::OK) {
        publication = {reply.result, reply.handle, legacy_hook_management.host};
        local.id = publication.id;
        state.publications.push_back(std::move(local));
    }
    return error;
}

KModErr hook_publication_prepare(const RequestPublicationMember* members, uint32_t count,
    uint32_t gate_index, uint64_t entry, uint64_t offset, uint32_t expected,
    uint64_t payload, RequestPublication& publication) {
    return maplesu_request_owner_mode() ? maplesu_request_owner_publication_prepare(
        members, count, gate_index, entry, offset, expected, payload, publication) :
        legacy_hook_prepare(members, count, gate_index, entry, offset, expected, payload, publication);
}

KModErr hook_publication_operation(uint32_t command, const RequestPublication& publication) {
    if (command != MAPLESU_REQUEST_PUBLICATION_PUBLISH &&
        command != MAPLESU_REQUEST_PUBLICATION_DISABLE &&
        command != MAPLESU_REQUEST_PUBLICATION_RETIRE) return KModErr::ERR_MODULE_PARAM;
    if (maplesu_request_owner_mode()) {
        if (command == MAPLESU_REQUEST_PUBLICATION_PUBLISH)
            return maplesu_request_owner_publication_publish(publication);
        if (command == MAPLESU_REQUEST_PUBLICATION_DISABLE)
            return maplesu_request_owner_publication_disable(publication);
        return maplesu_request_owner_publication_retire(publication);
    }
    if (!publication.id || !publication.control_address) return KModErr::ERR_MODULE_PARAM;
    auto error = legacy_hook_session();
    if (error != KModErr::OK) return error;
    if (publication.host.low != legacy_hook_management.host.low ||
        publication.host.high != legacy_hook_management.host.high)
        return KModErr::ERR_MODULE_ROOT_KEY;
    error = legacy_hook_manage(command, publication.id, 0, nullptr, 0);
    auto& state = legacy_hook_management;
    if (state.reply.completed && ((command == MAPLESU_REQUEST_PUBLICATION_PUBLISH && error == KModErr::OK) ||
            (state.reply.effects & MAPLESU_EFFECT_RELEASED))) {
        const auto local = std::find_if(state.publications.begin(), state.publications.end(),
            [&](const LegacyHookPublication& value) { return value.id == publication.id; });
        if (local != state.publications.end()) {
            for (const auto address : local->addresses) state.allocations.erase(address);
            if (state.reply.effects & MAPLESU_EFFECT_RELEASED) state.publications.erase(local);
        }
    }
    return error;
}

KModErr hook_publication_control(const RequestPublication& publication,
    maplesu_publication_control_v3& control) {
    if (maplesu_request_owner_mode())
        return maplesu_request_owner_publication_control(publication, control);
    if (!publication.id || !publication.control_address) return KModErr::ERR_MODULE_PARAM;
    auto error = legacy_hook_session();
    if (error != KModErr::OK) return error;
    if (publication.host.low != legacy_hook_management.host.low ||
        publication.host.high != legacy_hook_management.host.high)
        return KModErr::ERR_MODULE_ROOT_KEY;
    maplesu_publication_control_v3 candidate{};
    error = maplesu_request_v2_read(publication.control_address, &candidate, sizeof(candidate));
    if (error != KModErr::OK) return error;
    if (candidate.schema != 1 || candidate.length != sizeof(candidate) || candidate.reserved ||
        candidate.publication_id != publication.id || !candidate.epoch ||
        candidate.enter_function < UINT64_C(0xffff000000000000) || (candidate.enter_function & 3u) ||
        candidate.leave_function < UINT64_C(0xffff000000000000) || (candidate.leave_function & 3u) ||
        candidate.payload_entry < UINT64_C(0xffff000000000000) || (candidate.payload_entry & 3u) ||
        candidate.entry_address < UINT64_C(0xffff000000000000) || (candidate.entry_address & 3u) ||
        candidate.entry_address > UINT64_MAX - 4 || candidate.resume_address != candidate.entry_address + 4)
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    control = candidate;
    return KModErr::OK;
}
} // namespace

HookCleanupReceiptScope::HookCleanupReceiptScope() noexcept
    : legacy_reply_(legacy_hook_management.reply), process_(getpid()), saved_errno_(errno) {}
HookCleanupReceiptScope::~HookCleanupReceiptScope() noexcept {
    if (process_ == static_cast<uint64_t>(getpid())) {
        legacy_hook_management.reply = legacy_reply_;
        errno = saved_errno_;
    }
}

KModErr maplesu_hook_reserve_code(uint32_t bytes, uint64_t near_anchor, uint64_t& address) {
    if (maplesu_request_owner_mode()) return maplesu_request_owner_reserve_code(bytes, near_anchor, address);
    if (!bytes || bytes > MAPLESU_MANAGEMENT_MAX_BYTES || (near_anchor &&
        (near_anchor < UINT64_C(0xffff000000000000) || (near_anchor & 3u))))
        return KModErr::ERR_MODULE_PARAM;
    return legacy_hook_reserve(bytes, near_anchor, address);
}
KModErr maplesu_hook_write(uint64_t address, const void* data, uint32_t bytes) {
    return maplesu_request_owner_mode() ? write_kernel_mem(address, data, bytes, KernMemProt::KMP_RW) :
        legacy_hook_write(address, data, bytes);
}
KModErr maplesu_hook_release(uint64_t address) {
    return maplesu_request_owner_mode() ? free_kernel_mem(address) : legacy_hook_release(address);
}
RequestManagementReply maplesu_hook_reply() {
    return maplesu_request_owner_mode() ? maplesu_request_management_reply() : legacy_hook_management.reply;
}
KModErr maplesu_hook_close() {
    return maplesu_request_owner_mode() ? maplesu_request_owner_close() : legacy_hook_close();
}
KModErr maplesu_hook_shared_bool_create(bool initial, RequestSharedBool& object) {
    if (maplesu_request_owner_mode()) return maplesu_request_owner_shared_bool_create(initial, object);
    const uint8_t value = initial ? 1 : 0;
    const auto error = legacy_hook_manage(MAPLESU_REQUEST_SHARED_BOOL_CREATE, 0, 0, &value, sizeof(value));
    if (error != KModErr::OK) return error;
    const auto& state = legacy_hook_management;
    // Legacy's real provider uses epoch 1 as provenance. Authority comes from
    // the current Key EXEC scope and kernel session, never this scalar.
    object = {state.reply.handle, state.reply.result, state.host, 1};
    return KModErr::OK;
}
namespace {
KModErr hook_shared_bool_operation(uint32_t command, const RequestSharedBool& object, const uint8_t* value) {
    auto error = legacy_hook_session();
    if (error != KModErr::OK) return error;
    if (!object.id || !object.address || object.epoch != 1 ||
        object.host.low != legacy_hook_management.host.low || object.host.high != legacy_hook_management.host.high)
        return KModErr::ERR_MODULE_ROOT_KEY;
    return legacy_hook_manage(command, object.id, object.address, value, value ? 1 : 0);
}
}
KModErr maplesu_hook_shared_bool_store(const RequestSharedBool& object, bool value) {
    if (maplesu_request_owner_mode()) return maplesu_request_owner_shared_bool_store(object, value);
    const uint8_t candidate = value ? 1 : 0;
    return hook_shared_bool_operation(MAPLESU_REQUEST_SHARED_BOOL_STORE, object, &candidate);
}
KModErr maplesu_hook_shared_bool_release(const RequestSharedBool& object) {
    return maplesu_request_owner_mode() ? maplesu_request_owner_shared_bool_release(object) :
        hook_shared_bool_operation(MAPLESU_REQUEST_SHARED_BOOL_RELEASE, object, nullptr);
}
