#include "module_err_def.h"
#include "module_base_kernel_mem.h"
#include "kernel_read_metadata.h"
#include <limits>
#include <new>

namespace kernel_module {
KModErr maplesu_kernel_metadata_facts(RequestMetadataFacts& facts, bool& supported) {
    facts = {};
    supported = false;
    const auto auth = maplesu_request_auth_state();
    maplesu_metadata_v2 metadata{};
    uint64_t descriptor = 0;
    RequestOwnerBinding owner{};
    if (!maplesu_request_auth_legacy(auth)) {
        maplesu_metadata_owner_v3 snapshot{};
        const auto error = maplesu_request_owner_channel(snapshot, descriptor, owner);
        if (is_failed(error)) return error;
        metadata = snapshot.common;
    } else {
        bool negotiated = false;
        const auto error = maplesu_kernel_metadata_v2(metadata, descriptor, negotiated);
        if (is_failed(error)) return error;
        if (!negotiated) return auth == maplesu_request_auth_state()
            ? KModErr::OK : KModErr::ERR_MODULE_ROOT_KEY;
    }
    RequestMetadataFacts candidate{};
    candidate.wire_version = metadata.version;
    candidate.wire_length = metadata.length;
    candidate.descriptor = descriptor;
    candidate.capabilities = metadata.capabilities;
    candidate.append_bytes = maplesu_kernel_read::legacy_metadata_bytes(metadata.capabilities);
    if (!maplesu_kernel_read::metadata_address(descriptor, metadata.kernel_base_displacement,
            1, candidate.kernel_base) ||
        !maplesu_kernel_read::metadata_address(descriptor, metadata.legacy_metadata_displacement,
            candidate.append_bytes, candidate.append)) return KModErr::ERR_MODULE_INTERNAL_APPEND;
    candidate.layout_length = metadata.layout_length;
    candidate.layout_version = metadata.layout_version;
    if (metadata.layout_length && !maplesu_kernel_read::metadata_address(descriptor,
            metadata.layout_displacement, metadata.layout_length, candidate.layout))
        return KModErr::ERR_MODULE_INTERNAL_APPEND;
    candidate.owner = owner;
    if (auth != maplesu_request_auth_state() ||
        (!maplesu_request_auth_legacy(auth) && !maplesu_request_owner_binding_matches(owner)))
        return KModErr::ERR_MODULE_ROOT_KEY;
    facts = candidate;
    supported = true;
    return KModErr::OK;
}
} // namespace kernel_module

#if defined(MAPLESU_KERNEL_READ_CANONICAL)
#define MAPLESU_KERNEL_READ_ENTRY _ZN13kernel_module23request_kernel_read_memEmPvj
#else
#define MAPLESU_KERNEL_READ_ENTRY __wrap__ZN13kernel_module23request_kernel_read_memEmPvj
#endif
extern "C" __attribute__((visibility("hidden")))
KModErr MAPLESU_KERNEL_READ_ENTRY(uint64_t source, void* destination, uint32_t size) {
    if (!destination || !size || source < UINT64_C(0xffff000000000000) ||
        source > std::numeric_limits<uint64_t>::max() - (size - 1) ||
        reinterpret_cast<uintptr_t>(destination) >
            std::numeric_limits<uintptr_t>::max() - (size - 1))
        return KModErr::ERR_MODULE_PARAM;
    try {
        if (kernel_module::maplesu_request_auth_legacy(kernel_module::maplesu_request_auth_state()))
            return kernel_module::maplesu_kernel_legacy_read(source, destination, size);
        maplesu_metadata_owner_v3 metadata{};
        kernel_module::RequestOwnerBinding binding{};
        uint64_t descriptor = 0;
        const auto error = kernel_module::maplesu_request_owner_channel(metadata, descriptor, binding);
        if (is_failed(error)) return error;
        return kernel_module::maplesu_request_owner_read(source, destination, size, binding);
    } catch (const std::bad_alloc&) {
        return KModErr::ERR_NO_MEM;
    }
}
