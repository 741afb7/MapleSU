#pragma once
#include <stdint.h>

namespace maplesu_kernel_read {
// Fixed append-package descriptors used by the cold symbol resolver: data and
// kallsyms offsets, then avc_denied's six-byte record and filldir64's offset.
// The path string, intervening fields and all derived pointers stay excluded.
inline constexpr bool metadata_read_allowed(uint64_t base, uint64_t source, uint32_t size) {
    if (!base || !size || source < base) return false;
    const uint64_t offset = source - base;
    return (offset < 8 && size <= 8 - offset) ||
           (offset >= 0x2a && offset < 0x34 && size <= 0x34 - offset);
}
}

#include "module_err_def.h"
#include "request_protocol.h"
#include "maplesu_request_private.h"
#include <limits>

namespace maplesu_kernel_read {
inline constexpr uint32_t legacy_metadata_bytes(uint64_t capabilities) {
    if (capabilities & MAPLESU_CAP_APPEND64) return MAPLESU_APPEND64_BYTES;
    return capabilities & MAPLESU_CAP_APPEND_AUDIT
        ? MAPLESU_LEGACY_METADATA_AUDIT_BYTES : MAPLESU_LEGACY_METADATA_V2_BYTES;
}

inline bool metadata_address(uint64_t descriptor, int64_t displacement,
                             uint32_t length, uint64_t& address) {
    address = 0;
    if (!length) return false;
    uint64_t result = descriptor;
    if (displacement < 0) {
        const auto magnitude = static_cast<uint64_t>(-(displacement + 1)) + 1;
        if (magnitude > descriptor) return false;
        result -= magnitude;
    } else {
        if (static_cast<uint64_t>(displacement) > std::numeric_limits<uint64_t>::max() - descriptor)
            return false;
        result += static_cast<uint64_t>(displacement);
    }
    if (result < UINT64_C(0xffff000000000000) ||
        length - 1 > std::numeric_limits<uint64_t>::max() - result)
        return false;
    address = result;
    return true;
}

inline bool valid_metadata_fields(const maplesu_metadata_v2& metadata, uint64_t descriptor,
                                  uint64_t capabilities, uint16_t version, uint16_t bytes) {
    uint64_t address = 0;
    if ((descriptor & 7) || !metadata_address(descriptor, 0, bytes, address) ||
        metadata.magic != MAPLESU_METADATA_MAGIC || metadata.version != version ||
        metadata.length != bytes || metadata.capabilities != capabilities ||
        !metadata_address(descriptor, metadata.kernel_base_displacement, 1, address) ||
        !metadata_address(descriptor, metadata.legacy_metadata_displacement,
                          legacy_metadata_bytes(metadata.capabilities), address))
        return false;
    if (!metadata.layout_length)
        return !metadata.layout_displacement && !metadata.layout_version;
    return metadata.layout_version && metadata_address(descriptor, metadata.layout_displacement,
                                                       metadata.layout_length, address);
}
inline bool valid_metadata(const maplesu_metadata_v2& metadata, uint64_t descriptor,
                           uint64_t capabilities) {
    return valid_metadata_fields(metadata, descriptor, capabilities,
        MAPLESU_METADATA_VERSION, MAPLESU_METADATA_WIRE_BYTES);
}
}

namespace kernel_module {
struct RequestMetadataFacts {
    uint16_t wire_version = 0, wire_length = 0;
    uint64_t descriptor = 0, capabilities = 0;
    uint64_t kernel_base = 0, append = 0, layout = 0;
    uint32_t append_bytes = 0, layout_length = 0, layout_version = 0;
    RequestOwnerBinding owner{};
};
// Non-wire validated address facts. Owner snapshots retain v3/80 and binding;
// no truncated descriptor is relabelled as v2.
KModErr maplesu_kernel_metadata_facts(RequestMetadataFacts& facts, bool& supported);
// Explicit legacy selection only. An untouched QUERY never becomes cached
// evidence of an old gateway, and a negotiated READ error remains an error.
__attribute__((visibility("hidden")))
KModErr maplesu_kernel_metadata_v2(maplesu_metadata_v2& metadata,
                                 uint64_t& descriptor_address, bool& supported);
__attribute__((visibility("hidden")))
KModErr maplesu_kernel_legacy_read(uint64_t source, void* destination, uint32_t size);
}
