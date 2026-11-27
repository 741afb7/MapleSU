// SPDX-License-Identifier: GPL-3.0-only
#include <linux/version.h>
#include <linux/errno.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 15, 0)
#include <crypto/sha.h>
#else
#include <crypto/sha2.h>
#endif
#include "manager_apk_identity.h"
#include "manager_claim_entry.h"
#include "protected_install_kernel.h"
#include "manager_owner.h"
#include "manager_owner_claim.h"

static int msu_manager_sha256(const void *bytes, size_t length, u8 digest[32])
{
    if (length > (u32)~0U) return -EINVAL;
    sha256(bytes, (unsigned int)length, digest);
    return 0;
}

static const struct msu_internal_manager_policy msu_manager_policy = {
    .manager_package = "dev.maplesu.manager",
    .pin = {
        .certificate_size = 772,
        .certificate_sha256 = {
            0x30, 0xba, 0xfb, 0x57, 0xe3, 0x60, 0x4a, 0xfe,
            0x93, 0xda, 0x13, 0xa1, 0x28, 0x55, 0x16, 0xe0,
            0x43, 0xfe, 0xb8, 0x2b, 0x56, 0xa8, 0x61, 0xa1,
            0x57, 0xb5, 0x3c, 0xca, 0xa2, 0x7c, 0x35, 0x06,
        },
        .minimum_generation = 2,
    },
    .sha256 = msu_manager_sha256,
};

int msu_manager_claim_entry(const struct msu_manager_claim_boot_policy *boot,
                            const char *name)
{
    msu_claim_address address;
    u32 generation;
    size_t i;
    char credential[MSU_MANAGER_CLAIM_KEY_BYTES];
    int delivered;

    if (!boot || !name || boot->version != MSU_MANAGER_CLAIM_BOOT_VERSION ||
        boot->reserved || !boot->credential) return 0;
    address = msu_manager_claim_address(name,
        strnlen(name, MSU_MANAGER_CLAIM_NAME_BYTES + 1));
    if (!address) return 0;
    for (i = 0; i < MSU_MANAGER_CLAIM_KEY_BYTES; ++i) {
        unsigned char c = boot->credential[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
              (c >= 'A' && c <= 'Z'))) return 0;
    }
    if (msu_kernel_verify_manager_installation(&msu_manager_policy, &generation))
        return 0;
    if (generation < msu_manager_policy.pin.minimum_generation) return 0;
    /* Hardened usercopy requires a live stack copy rather than a text source. */
    memcpy(credential, boot->credential, sizeof(credential));
    delivered = copy_to_user_nofault((void __user *)(unsigned long)address,
                                    credential, sizeof(credential)) == 0;
    memzero_explicit(credential, sizeof(credential));
    return delivered;
}

int msu_manager_owner_claim_entry(struct msu_manager_owner *owner, u32 *epoch)
{
    if (epoch) *epoch = 0;
    if (!owner || !epoch) return -EINVAL;
    return msu_manager_owner_claim(owner, &msu_manager_policy, epoch);
}
