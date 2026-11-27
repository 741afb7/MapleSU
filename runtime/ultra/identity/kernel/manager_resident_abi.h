// SPDX-License-Identifier: GPL-3.0-only
#ifndef MAPLESU_MANAGER_RESIDENT_ABI_H
#define MAPLESU_MANAGER_RESIDENT_ABI_H
#include "manager_claim_loader.h"
#include "manager_deployment_namespace.h"

#define MSU_RESIDENT_BOOT_MAGIC 0x5242534dU
#define MSU_RESIDENT_EMPTY 0U
#define MSU_RESIDENT_INITIALIZING 1U
#define MSU_RESIDENT_READY 2U
#define MSU_RESIDENT_QUARANTINED 3U

/* This object belongs to the kernel Image's reserved, non-init RW/NX memory,
 * not to the materialized payload. The one-shot boot entry clears all 64 bytes
 * before publishing boot_magic. No request initializes or resets this object. */
struct msu_resident_slot {
    msu_loader_u32 boot_magic, state;
    msu_loader_u64 memory, dispatch;
    msu_loader_u32 allocation_bytes;
    int last_error;
    msu_loader_u64 reserved[4];
};

/* Immutable, image-generated v2 anchor extension. Function offsets are relative
 * to the materialized ELF; slot_offset is relative to the running kernel Image. */
struct msu_resident_binding {
    msu_loader_u32 slot_offset, image_memory_bytes;
    msu_loader_u32 init_offset, discard_offset, dispatch_offset;
    msu_loader_u32 append_offset, append_bytes, reserved;
};

#define MSU_RESIDENT_DEPLOYMENT_BINDING_VERSION 1U
/* Anchor version 4 explicitly selects this sized binding, not the old 32B one.
 * CRC covers all binding bytes with crc32 zero, followed by the namespace record. */
struct msu_resident_deployment_binding {
    msu_loader_u32 version, bytes;
    struct msu_resident_binding resident;
    msu_loader_u32 namespace_offset, namespace_bytes, crc32, reserved;
};

struct msu_resident_config_v2 {
    msu_loader_u32 version, config_bytes, append_bytes, namespace_record_bytes;
    const void *image;
    const void *append;
    const char *credential; /* Optional historical claim; owner-v3 does not consume it. */
    const struct msu_deployment_namespace_record *namespace_record;
};

static inline msu_loader_u32 msu_resident_deployment_crc(
    const struct msu_resident_deployment_binding *binding,
    const struct msu_deployment_namespace_record *record)
{
    const unsigned char *bytes = (const void *)binding;
    msu_loader_u32 crc = ~0U;
    unsigned int i, bit;
    for (i = 0; i < sizeof(*binding) + sizeof(*record); ++i) {
        unsigned char value = i < sizeof(*binding) ?
            (i >= 48 && i < 52 ? 0 : bytes[i]) : ((const unsigned char *)record)[i - sizeof(*binding)];
        crc ^= value;
        for (bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return ~crc;
}

struct msu_resident_config {
    msu_loader_u32 version, append_bytes;
    const void *image;
    const void *append;
    const char *credential; /* Optional historical claim; NULL keeps owner-v3 available. */
};

/* Private paired-support contract. The prefix is allocated separately from the
 * resident image. Its delayed_work immediately follows; native compile-time
 * assertions bind that offset without guessing the kernel workqueue layout.
 * queue/free/wq refer directly to kernel Image imports, never resident thunks. */
struct work_struct;
struct delayed_work;
struct workqueue_struct;
struct msu_manager_claim_anchor;
struct msu_resident_retirement {
    const struct msu_manager_claim_anchor *anchor;
    struct msu_resident_slot *slot;
    void *memory;
    msu_loader_u64 allocation_bytes, delay, max_delay;
    struct workqueue_struct *wq;
    _Bool (*queue)(int, struct workqueue_struct *, struct delayed_work *, unsigned long);
    void (*free)(const void *);
    int (*discard)(void);
    msu_loader_u32 caller_active, drained;
    int cleanup_error;
    msu_loader_u32 attempts, cpu;
    int first_cleanup_error;
};

struct msu_resident_config_v3 {
    struct msu_resident_config_v2 base;
    const struct msu_manager_claim_anchor *anchor;
    struct msu_resident_slot *slot;
    void *memory;
    msu_loader_u64 allocation_bytes;
    void (*retire_work)(struct work_struct *);
};
_Static_assert(sizeof(struct msu_resident_retirement) == 104, "retirement prefix layout");
_Static_assert(sizeof(struct msu_resident_config_v3) == 88, "private resident config v3 layout");

_Static_assert(sizeof(struct msu_resident_slot) == 64, "resident slot layout");
_Static_assert(sizeof(struct msu_resident_binding) == 32, "resident binding layout");
_Static_assert(sizeof(struct msu_resident_config) == 32, "resident config layout");
_Static_assert(sizeof(struct msu_resident_deployment_binding) == 56, "deployment binding layout");
_Static_assert(__builtin_offsetof(struct msu_resident_deployment_binding, crc32) == 48, "deployment CRC offset");
_Static_assert(sizeof(struct msu_resident_config_v2) == 48, "resident config v2 layout");
_Static_assert(__builtin_offsetof(struct msu_resident_config_v2, namespace_record) == 40, "namespace config offset");
#endif
