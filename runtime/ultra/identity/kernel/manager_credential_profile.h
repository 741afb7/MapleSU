// SPDX-License-Identifier: GPL-3.0-only
#ifndef MAPLESU_MANAGER_CREDENTIAL_PROFILE_H
#define MAPLESU_MANAGER_CREDENTIAL_PROFILE_H

#include <linux/types.h>
#include "request_protocol.h"

#define MSU_CRED_PROFILE_MAX_GROUPS 32U
#define MSU_CRED_PROFILE_DOMAIN_BYTES 64U
#define MSU_CRED_CAP_INHERITABLE (1U << 0)
#define MSU_CRED_CAP_PERMITTED (1U << 1)
#define MSU_CRED_CAP_EFFECTIVE (1U << 2)
#define MSU_CRED_CAP_BOUNDING (1U << 3)
#define MSU_CRED_CAP_AMBIENT (1U << 4)
#define MSU_CRED_CAP_ALL ((1U << 5) - 1)

struct cred;

#ifdef MSU_BOOT_SDK_NATIVE
/* Authenticated ROOT transaction only, before its single credential commit. */
int msu_native_root_prepare(struct cred *prepared);
#endif

/* Normalized immutable kernel input, not an authority or a userspace wire.
 * Default/template selection belongs to the pinned business Profile producer.
 * Unselected capabilities and IDs/groups retain the prepared credential copy.
 * ID replacement sets real/effective/saved/filesystem IDs to uid/gid in the
 * supported user namespace. Empty domain inherits; a nonempty domain needs
 * the separately qualified native prepared-credential LSM operation. */
struct msu_credential_profile {
    u32 uid, gid, groups_count;
    u32 groups[MSU_CRED_PROFILE_MAX_GROUPS];
    maplesu_caps_v3 caps;
    u32 cap_write_mask;
    bool replace_ids, replace_groups, clear_securebits;
    char selinux_domain[MSU_CRED_PROFILE_DOMAIN_BYTES];
};

/* Structure/target-support admission only; it grants no runtime qualification.
 * Domain existence is checked by the native resolver before credential commit.
 * Runtime callers retain their complete pinned input and qualification lease. */
int msu_credential_profile_validate(const struct msu_credential_profile *profile);

/* The one shared prepare/commit/abort transaction. validate and adopt are
 * mandatory trusted callbacks. validate runs before prepare
 * and commit against the caller's held admission context. adopt runs only after
 * the exact prepared credential has been committed and observed as both current
 * credentials. Its before/after references are borrowed for the synchronous
 * callback; a consumer retaining after must acquire its own reference.
 * adopt may rebind that held execution's active identity, never grant authority.
 * An adopt error retains COMMITTED and never aborts or restores the old cred.
 * Task/seccomp, task flags, namespace and module umount remain separate stages.
 * No user-supplied callback or claim crosses this private kernel interface. */
int msu_credential_replace_profile_current(const struct msu_credential_profile *profile,
    int (*validate)(void *context),
    int (*adopt)(void *context, const struct cred *before, const struct cred *after),
    void *context, u32 *effects);

#endif
