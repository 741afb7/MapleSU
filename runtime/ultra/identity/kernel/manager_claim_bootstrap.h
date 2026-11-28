// SPDX-License-Identifier: GPL-3.0-only
#ifndef MAPLESU_MANAGER_CLAIM_BOOTSTRAP_H
#define MAPLESU_MANAGER_CLAIM_BOOTSTRAP_H
#include "manager_claim_loader.h"
#include "manager_claim_entry.h"
#include "manager_resident_abi.h"

#define MSU_CLAIM_ANCHOR_MAGIC 0x4143534dU
#define MSU_CLAIM_ANCHOR_VERSION 1U
#define MSU_CLAIM_RESIDENT_ANCHOR_VERSION 2U
#define MSU_CLAIM_BOOT_ANCHOR_VERSION 3U
#define MSU_CLAIM_DEPLOYMENT_ANCHOR_VERSION 4U
#define MSU_CLAIM_SOURCE_ANCHOR_VERSION 5U
struct msu_manager_claim_anchor {
    msu_loader_u32 magic, version, self_offset, page_shift;
    msu_loader_u32 materializer_offset, credential_offset, allocate_offset, release_offset;
    msu_loader_u32 set_nx_offset, set_rw_offset, set_ro_offset, set_x_offset;
    msu_loader_u32 cache_offset, allocator_kind;
    struct msu_loader_binding binding;
    msu_loader_u32 reserved[4];
};
_Static_assert(sizeof(struct msu_manager_claim_anchor) == 96, "claim anchor layout");
_Static_assert(__builtin_offsetof(struct msu_manager_claim_anchor, binding) == 56,
               "claim binding offset");

/* New support accepts resident v2/v3/v4 anchors. The original v1 anchor and
 * bootstrap asset bytes retain their existing compatibility path separately.
 * The host accepts the current-task v3 protocol; historical Key claims are ignored.
 * Both arguments come from the bound exec entry; name is kernel-owned.
 * A v3 anchor is initialized by msu_manager_resident_boot after basic initcalls;
 * requests dispatch READY only. V4 explicitly selects deployment binding/config
 * and msu_manager_resident_boot_deployment; v2 retains its existing lazy path.
 * reserved[0] is the immutable resident binding and reserved[1] is the required
 * permissions helper offset; reserved[2..3] stay zero. The boot producer has
 * initialized the RW/NX slot before userspace; requests never reset it. */
void msu_manager_claim_bootstrap(const struct msu_manager_claim_anchor *, const char *name);
int msu_manager_resident_boot(const struct msu_manager_claim_anchor *);
int msu_manager_resident_boot_deployment(const struct msu_manager_claim_anchor *);
/* The boot owner supplies and retains the verified source mapping until this
 * call returns, including failure and quarantine paths. Requests observe READY
 * only and never initialize a v5 anchor or obtain a source pointer. */
int msu_manager_resident_boot_deployment_source(const struct msu_manager_claim_anchor *,
    const void *source_base, msu_loader_u32 source_bytes);
int msu_manager_claim_permissions(const struct msu_manager_claim_anchor *,
    const struct msu_loader_header *, void *memory, int pages);
#endif
