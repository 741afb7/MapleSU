// SPDX-License-Identifier: GPL-3.0-only
#ifndef MAPLESU_MANAGER_CLAIM_ENTRY_H
#define MAPLESU_MANAGER_CLAIM_ENTRY_H

#include "manager_claim_abi.h"
#include "manager_apk_identity.h"

#define MSU_MANAGER_CLAIM_BOOT_VERSION 1U

/* Optional historical credential, independent of owner-v3 installation identity. */
struct msu_manager_claim_boot_policy {
    msu_apk_u32 version;
    msu_apk_u32 reserved;
    const char *credential;
};

_Static_assert(sizeof(struct msu_manager_claim_boot_policy) == 16,
               "claim boot policy layout");

/* Delivers 48 bytes only to the protected, pinned Manager installation. */
int msu_manager_claim_entry(const struct msu_manager_claim_boot_policy *boot,
                            const char *name);

#endif
