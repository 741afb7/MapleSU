// SPDX-License-Identifier: GPL-3.0-only
#ifndef MAPLESU_MANAGER_CLAIM_ABI_H
#define MAPLESU_MANAGER_CLAIM_ABI_H

#ifdef __KERNEL__
#include <linux/types.h>
typedef u64 msu_claim_address;
#else
#include <stddef.h>
#include <stdint.h>
typedef uint64_t msu_claim_address;
#endif

/* Historical address-only Key-claim framing. New keyless hosts ignore these
 * names; current-task v3 QUERY verifies the installed APK instead. Retained
 * declarations identify old artifacts, not a credential recovery fallback. */
#define MSU_MANAGER_CLAIM_PREFIX "/.msu-ultra-identity-v1/"
#define MSU_MANAGER_CLAIM_ADDRESS_HEX 16U
#define MSU_MANAGER_CLAIM_KEY_BYTES 48U
#define MSU_MANAGER_CLAIM_NAME_BYTES \
    (sizeof(MSU_MANAGER_CLAIM_PREFIX) - 1 + MSU_MANAGER_CLAIM_ADDRESS_HEX)

static inline void msu_manager_claim_name(char name[MSU_MANAGER_CLAIM_NAME_BYTES + 1],
                                          msu_claim_address address)
{
    static const char prefix[] = MSU_MANAGER_CLAIM_PREFIX;
    static const char hex[] = "0123456789abcdef";
    size_t i;
    for (i = 0; i < sizeof(prefix) - 1; ++i)
        name[i] = prefix[i];
    for (i = 0; i < MSU_MANAGER_CLAIM_ADDRESS_HEX; ++i)
        name[sizeof(prefix) - 1 + i] = hex[(address >> ((15U - i) * 4U)) & 15U];
    name[MSU_MANAGER_CLAIM_NAME_BYTES] = '\0';
}

static inline msu_claim_address msu_manager_claim_address(const char *name, size_t length)
{
    static const char prefix[] = MSU_MANAGER_CLAIM_PREFIX;
    msu_claim_address address = 0;
    size_t i;
    if (!name || length != MSU_MANAGER_CLAIM_NAME_BYTES)
        return 0;
    for (i = 0; i < sizeof(prefix) - 1; ++i)
        if (name[i] != prefix[i])
            return 0;
    for (; i < length; ++i) {
        const unsigned char value = (unsigned char)name[i];
        unsigned int digit;
        if (value >= '0' && value <= '9')
            digit = value - '0';
        else if (value >= 'a' && value <= 'f')
            digit = value - 'a' + 10U;
        else
            return 0;
        address = (address << 4) | digit;
    }
    return address;
}

#endif
