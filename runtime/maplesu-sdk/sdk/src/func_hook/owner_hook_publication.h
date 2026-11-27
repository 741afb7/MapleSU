#pragma once

#include "../../../../ultra/authorization/publication_return_private.h"

namespace {
struct OwnerHookRecord {
    std::unique_ptr<HookContext> context;
    RequestPublication publication{};
    bool published = false;
    bool retired = false;
    bool pending = false;
    bool legacy = false;
};
std::map<HookHandle, OwnerHookRecord> owner_hooks;

bool owner_cleanup_needs_close(const RequestManagementReply& reply) {
    return !reply.completed || reply.status == MAPLESU_REQUEST_STATUS_DENIED ||
        reply.status == MAPLESU_REQUEST_STATUS_STALE || (reply.effects & MAPLESU_EFFECT_SESSION_CLOSED);
}

auto find_owner_hook(HookHandle handle) {
    auto found = owner_hooks.find(handle);
    if (found != owner_hooks.end() || !handle) return found;
    return std::find_if(owner_hooks.begin(), owner_hooks.end(), [&](const auto& item) {
        const auto& value = *item.second.context;
        return value.entry == handle->entry && value.original == handle->original &&
            value.trampoline == handle->trampoline && value.size == handle->size &&
            value.disable_flag == handle->disable_flag;
    });
}

KModErr retire_owner_hook(OwnerHookRecord& record) {
    if (record.retired) return KModErr::OK;
    const KModErr error = hook_publication_operation(MAPLESU_REQUEST_PUBLICATION_RETIRE, record.publication);
    if (error != KModErr::OK) return error;
    const auto reply = maplesu_hook_reply();
    const uint32_t retained = MAPLESU_EFFECT_PENDING | MAPLESU_EFFECT_QUARANTINED |
        MAPLESU_EFFECT_PUBLICATION_RETAINED | MAPLESU_EFFECT_GATE_PINNED;
    if (!reply.completed || !(reply.effects & MAPLESU_EFFECT_RELEASED) ||
        (reply.effects & retained)) {
        record.pending = true;
        return KModErr::ERR_MODULE_MUST_UNINSTALL;
    }
    record.context->enabled = 0;
    record.retired = true;
    record.pending = false;
    published_entries.erase(record.context->entry);
    return KModErr::OK;
}

KModErr uninstall_owner_hook(OwnerHookRecord& record) {
    if (record.legacy == maplesu_request_owner_mode()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (record.retired) return KModErr::OK;
    record.pending = true;
    const auto last = maplesu_hook_reply();
    if (owner_cleanup_needs_close(last)) {
        const auto close_error = maplesu_hook_close();
        if (close_error != KModErr::OK) return close_error;
    }
    if (record.published) {
        const KModErr error = hook_publication_operation(MAPLESU_REQUEST_PUBLICATION_DISABLE, record.publication);
        if (error != KModErr::OK) return error;
        record.context->enabled = 0;
    }
    return retire_owner_hook(record);
}

// Before PREPARE only. A prepared closure is released exclusively by RETIRE.
struct OwnerHookStaging {
    uint64_t gate = 0, payload = 0, pool = 0;
    bool transferred = false;
    ~OwnerHookStaging() {
        if (transferred) return;
        const HookCleanupReceiptScope preserve_receipt;
        try {
            if (owner_cleanup_needs_close(maplesu_hook_reply())) {
                (void)maplesu_hook_close();
                return;
            }
        } catch (...) { return; }
        for (const uint64_t address : {pool, payload, gate}) {
            if (!address) continue;
            try {
                (void)maplesu_hook_release(address);
                if (owner_cleanup_needs_close(maplesu_hook_reply())) {
                    (void)maplesu_hook_close();
                    return;
                }
            } catch (...) {
                try { (void)maplesu_hook_close(); } catch (...) {}
                return;
            }
        }
    }
};

KModErr install_owner_hook(uint64_t kaddr, const void* buffer, uint32_t size,
                          bool after, HookContext** output,
                          const RequestSharedBool* dependencies = nullptr, uint32_t count = 0,
                          const OwnedHookActor* actor = nullptr, bool* actor_transferred = nullptr) {
    if (output) *output = nullptr;
    // Both authenticated backends use the kernel-owned 64-instance pool
    // and overflow provider, with one owner spanning original and callback.
    if (actor_transferred) *actor_transferred = false;
    if (actor && (after || !actor_transferred ||
        actor->allocation < UINT64_C(0xffff000000000000) || (actor->allocation & 3u) ||
        actor->bytes < 8 || (actor->bytes & 3u) || actor->bytes > MAPLESU_MANAGEMENT_MAX_BYTES ||
        actor->allocation > UINT64_MAX - actor->bytes ||
        actor->target < UINT64_C(0xffff000000000004) || actor->target > UINT64_MAX - 4 ||
        (actor->target & 3u)))
        return KModErr::ERR_MODULE_PARAM;
    const uint32_t local_count = actor ? 3u : 2u;
    if (count > 64 - local_count || (count && (!dependencies ||
        reinterpret_cast<uintptr_t>(dependencies) > UINTPTR_MAX - (count * sizeof(*dependencies) - 1))))
        return KModErr::ERR_MODULE_PARAM;
    for (uint32_t i = 0; i < count; ++i) {
        if (!dependencies[i].id || dependencies[i].address < UINT64_C(0xffff000000000000) ||
            !(dependencies[i].host.low || dependencies[i].host.high) || !dependencies[i].epoch)
            return KModErr::ERR_MODULE_PARAM;
        for (uint32_t j = 0; j < i; ++j)
            if (dependencies[i].id == dependencies[j].id) return KModErr::ERR_MODULE_PARAM;
    }
    if (!kaddr || (kaddr & 3u) || kaddr > UINT64_MAX - 8)
        return KModErr::ERR_MODULE_ADDR_NOT_ALGIN4;
    if (!buffer || size < 64 || (size & 3u) || size > MAPLESU_MANAGEMENT_MAX_BYTES - 32 ||
        reinterpret_cast<uintptr_t>(buffer) > UINTPTR_MAX - (size - 1u))
        return kErrHookFuncNotStandard;
    const char* first = static_cast<const char*>(buffer);
    const char* last = first + size;
    const char* begin_marker = after ? kMyAfterHookBeginFlag : kMyBeforeHookBeginFlag;
    const char* end_marker = after ? kMyAfterHookEndFlag : kMyBeforeHookEndFlag;
    if (std::search(first, last, begin_marker, begin_marker + 64) == last ||
        std::search(first, last, end_marker, end_marker + 64) == last)
        return kErrHookFuncNotStandard;
    const int64_t origin_slot = find_origin_kaddr_slot_offset(buffer, size);
    const int64_t return_slot = after ? 0 : find_ret_insn_slot_offset(buffer, size);
    if (origin_slot < 0 || uint64_t(origin_slot) + 8 > size ||
        (!after && (return_slot < 0 || uint64_t(return_slot) + 8 > size)))
        return kErrHookFuncNotStandard;

    uint32_t first_instruction = 0;
    KModErr error = read_kernel_mem(kaddr, &first_instruction, sizeof(first_instruction));
    if (error != KModErr::OK) return error;
    if (first_instruction == 0xd4200080u) {
        error = read_brk_4_kprobe_original_insn(kaddr, first_instruction);
        if (error != KModErr::OK) return error;
    }
    uint32_t expected = first_instruction;
    std::vector<InsnRecord> records{{kaddr, first_instruction}};
    const bool bti = (first_instruction & 0xffffff3fu) == 0xd503241fu;
    const uint32_t pac = first_instruction + 0x2afcdce1u;
    const bool pac_prologue = uint32_t((pac << 27) | (pac >> 5)) < 4u;
    uint64_t entry = kaddr;
    if (bti || pac_prologue) {
        entry += 4;
        error = read_kernel_mem(entry, &expected, sizeof(expected));
        if (error != KModErr::OK) return error;
        if (expected == 0xd4200080u) {
            error = read_brk_4_kprobe_original_insn(entry, expected);
            if (error != KModErr::OK) return error;
        }
        records.push_back({entry, expected});
    }
    if (published_entries.count(entry)) return KModErr::ERR_MODULE_DUPLICATE;

    OwnerHookStaging staging;
    // All permanent entrances and exits share this single reserved code region.
    constexpr uint32_t gate_bytes = 4096;
    error = maplesu_hook_reserve_code(gate_bytes, entry, staging.gate);
    if (error != KModErr::OK) return error;
    const uint32_t payload_bytes = after ? size : size + 32;
    error = maplesu_hook_reserve_code(payload_bytes, 0, staging.payload);
    if (error != KModErr::OK) return error;
    std::vector<RequestPublicationMember> members{
        {staging.gate, MAPLESU_PUBLICATION_GATE_CODE, {}},
        {staging.payload, MAPLESU_PUBLICATION_PAYLOAD_CODE, {}}};
    if (actor) members.push_back({actor->allocation, MAPLESU_PUBLICATION_PAYLOAD_CODE, {}});
    for (uint32_t i = 0; i < count; ++i)
        members.push_back({dependencies[i].address, MAPLESU_PUBLICATION_SHARED_BOOL, dependencies[i]});

    auto context = std::unique_ptr<HookContext>(new (std::nothrow)
        HookContext{entry, expected, staging.payload, payload_bytes, 0, 0});
    if (!context) return KModErr::ERR_NO_MEM;
    HookHandle handle = context.get();
    auto inserted = owner_hooks.emplace(handle, OwnerHookRecord{std::move(context), {}, false, false, false, !maplesu_request_owner_mode()});
    if (!inserted.second) return KModErr::ERR_MODULE_DUPLICATE;
    OwnerHookRecord& record = inserted.first->second;
    try {
        published_entries.insert(entry);
    } catch (...) {
        published_entries.erase(entry);
        owner_hooks.erase(inserted.first);
        throw;
    }

    error = hook_publication_prepare(members.data(), local_count + count,
        0, entry, 0, expected, staging.payload, record.publication);
    const RequestManagementReply prepare_reply = maplesu_hook_reply();
    if (error != KModErr::OK) {
        const HookCleanupReceiptScope preserve_receipt;
        // An uncertain PREPARE may already own these resources; CLOSE handles
        // unexposed session state. Never free addresses whose ownership is lost.
        staging.transferred = !prepare_reply.completed ||
            (prepare_reply.effects & (MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_QUARANTINED));
        if (actor_transferred) *actor_transferred = staging.transferred;
        if (!prepare_reply.completed) {
            // CLOSE reconciles both a local pre-submit failure and a PREPARE
            // whose reply was lost, without replaying the uncertain request.
            try { (void)maplesu_hook_close(); } catch (...) {}
        }
        published_entries.erase(entry);
        owner_hooks.erase(inserted.first);
        return error;
    }
    staging.transferred = true;
    if (actor_transferred) *actor_transferred = true;
    record.context->disable_flag = record.publication.control_address;

    bool publish_started = false;
    auto complete_failure = [&](KModErr cause) {
        const HookCleanupReceiptScope preserve_receipt;
        KModErr cleanup = KModErr::ERR_MODULE_ASM;
        try {
            const auto last = maplesu_hook_reply();
            const bool needs_close = owner_cleanup_needs_close(last);
            if (needs_close) {
                cleanup = maplesu_hook_close();
                if (cleanup == KModErr::OK && !publish_started) {
                    record.retired = true;
                    published_entries.erase(entry);
                } else if (cleanup == KModErr::OK) {
                    cleanup = retire_owner_hook(record);
                }
            } else {
                cleanup = retire_owner_hook(record);
            }
        } catch (...) {}
        if (cleanup == KModErr::OK) owner_hooks.erase(handle);
        else {
            record.pending = true;
            if (output) *output = handle;
        }
        return cause;
    };
    try {
        maplesu_publication_control_v3 control{};
        error = hook_publication_control(record.publication, control);
        if (error != KModErr::OK) return complete_failure(error);
        if (control.payload_entry != staging.payload || control.entry_address != entry)
            return complete_failure(KModErr::ERR_MODULE_TEST_CHANNEL);
        maplesu_publication_return_private return_control{};
        {
            if (record.publication.control_address > UINT64_MAX -
                    (MAPLESU_PRIVATE_RETURN_CONTROL_OFFSET + sizeof(return_control)))
                return complete_failure(KModErr::ERR_MODULE_TEST_CHANNEL);
            error = read_kernel_mem(record.publication.control_address +
                MAPLESU_PRIVATE_RETURN_CONTROL_OFFSET, &return_control, sizeof(return_control));
            if (error != KModErr::OK) return complete_failure(error);
            if (return_control.publication_id != control.publication_id ||
                return_control.schema != MAPLESU_PRIVATE_RETURN_CONTROL_SCHEMA ||
                return_control.length != sizeof(return_control))
                return complete_failure(KModErr::ERR_MODULE_TEST_CHANNEL);
        }
        if (actor) {
            if (return_control.actor_attach_function < UINT64_C(0xffff000000000000) ||
                (return_control.actor_attach_function & 3u))
                return complete_failure(KModErr::ERR_MODULE_TEST_CHANNEL);
            aarch64_asm_ctx attach_context = init_aarch64_asm();
            auto attach = attach_context.assembler();
            if (!attach) return complete_failure(KModErr::ERR_MODULE_ASM);
            arm64_module_asm_func_start(attach);
            aarch64_asm_mov_x(attach, x0, record.publication.id);
            aarch64_asm_mov_x(attach, x1, actor->allocation);
            aarch64_asm_mov_x(attach, x2, actor->target);
            aarch64_asm_mov_x(attach, x3, actor->kcfi);
            aarch64_asm_mov_x(attach, x16, return_control.actor_attach_function);
            attach->blr(x16);
            arm64_module_asm_func_end(attach, x0);
            if (attach_context.has_error()) return complete_failure(KModErr::ERR_MODULE_ASM);
            uint64_t result = 0;
            error = execute_kernel_asm_func(aarch64_asm_to_bytes(attach), result);
            if (error != KModErr::OK) return complete_failure(error);
            const int native_error = static_cast<int32_t>(result);
            if (native_error) {
                errno = native_error < 0 ? -native_error : EIO;
                return complete_failure(KModErr::ERR_MODULE_TEST_CHANNEL);
            }
        }
        aarch64_asm_ctx gate_context = init_aarch64_asm();
        OwnerGateLayout layout;
        error = after ? emit_owner_after(gate_context.assembler(), staging.gate,
            control, records, return_control.acquire_function, return_control.release_function,
            return_control.caller_x28_function, return_control.caller_lr_function,
            return_control.payload_function, layout) :
            emit_owner_before(gate_context.assembler(), staging.gate, control, records,
                return_control.acquire_function, return_control.release_function,
                return_control.caller_x28_function, return_control.caller_lr_function,
                return_control.payload_function, layout);
        if (error != KModErr::OK) return complete_failure(error);
        std::vector<uint8_t> gate = aarch64_asm_to_bytes(gate_context.assembler());
        if (gate.empty() || gate.size() > gate_bytes || layout.origin_offset >= gate.size() ||
            (!after && layout.early_return_offset >= gate.size()))
            return complete_failure(kErrAsmRetTooLong);
        gate.resize(gate_bytes, 0);

        std::vector<uint8_t> payload(payload_bytes, 0);
        std::memcpy(payload.data(), buffer, size);
        const uint64_t origin = staging.gate + layout.origin_offset;
        std::memcpy(payload.data() + origin_slot, &origin, sizeof(origin));
        if (!after) {
            const uint32_t veneer_offset = static_cast<uint32_t>(
                ((staging.payload + size + 7u) & ~UINT64_C(7)) - staging.payload);
            aarch64_asm_ctx veneer_context = init_aarch64_asm();
            if (!maplesu::hook_gate::emit_early_return_veneer(veneer_context.assembler(),
                    staging.gate + layout.early_return_offset))
                return complete_failure(kErrAsmBranchEncode);
            const auto veneer = aarch64_asm_to_bytes(veneer_context.assembler());
            if (veneer.empty() || veneer.size() > payload_bytes - veneer_offset)
                return complete_failure(kErrAsmRetTooLong);
            uint32_t branch = 0;
            if (!maplesu::aarch64::encode_b(staging.payload + return_slot,
                    staging.payload + veneer_offset, branch)) return complete_failure(kErrAsmBranchEncode);
            std::memcpy(payload.data() + return_slot, &branch, sizeof(branch));
            std::memcpy(payload.data() + veneer_offset, veneer.data(), veneer.size());
        }
        error = write_and_readback(staging.payload, payload.data(), payload_bytes, KernMemProt::KMP_RW);
        if (error == KModErr::OK)
            error = write_and_readback(staging.gate, gate.data(), gate_bytes, KernMemProt::KMP_RW);
        if (error != KModErr::OK) return complete_failure(error);
        publish_started = true;
        error = hook_publication_operation(MAPLESU_REQUEST_PUBLICATION_PUBLISH, record.publication);
        if (error != KModErr::OK) return complete_failure(error);
        const auto publish_reply = maplesu_hook_reply();
        const uint32_t publication_effects = MAPLESU_EFFECT_PUBLICATION_PUBLISHED | MAPLESU_EFFECT_PUBLICATION_RETAINED;
        const uint32_t text_effects = MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN;
        if (!publish_reply.completed ||
            !((publish_reply.result == 0 && publish_reply.effects == publication_effects) ||
              (publish_reply.result == 4 && publish_reply.effects == (publication_effects | text_effects))))
            return complete_failure(KModErr::ERR_MODULE_ASM);
        record.published = true;
        record.context->enabled = 1;
        if (output) *output = handle;
        return KModErr::OK;
    } catch (const std::bad_alloc&) {
        return complete_failure(KModErr::ERR_NO_MEM);
    } catch (...) {
        return complete_failure(KModErr::ERR_MODULE_ASM);
    }
}
} // namespace
