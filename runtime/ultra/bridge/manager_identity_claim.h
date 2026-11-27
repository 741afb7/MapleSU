// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include "../identity/kernel/manager_claim_abi.h"
#include <cstdint>
#include <string>
#include <sys/syscall.h>
#include <unistd.h>

namespace maplesu {
struct ManagerClaimReply {
    alignas(8) char bytes[MSU_MANAGER_CLAIM_KEY_BYTES]{};
    ~ManagerClaimReply() {
        volatile char* at = bytes;
        for (size_t i = 0; i < sizeof(bytes); ++i) at[i] = 0;
    }
};

/* The kernel verifies installation identity. This reply remains a candidate
 * until a separate live SDK Root transaction verifies uid 0. */
template <typename Execute>
inline std::string manager_identity_candidate_with(Execute&& execute) {
    ManagerClaimReply reply;
    char name[MSU_MANAGER_CLAIM_NAME_BYTES + 1];
    msu_manager_claim_name(name, reinterpret_cast<uintptr_t>(reply.bytes));
    execute(name);
    for (const unsigned char value : reply.bytes) {
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'z') ||
              (value >= 'A' && value <= 'Z'))) return {};
    }
    return std::string(reply.bytes, sizeof(reply.bytes));
}

inline std::string manager_identity_candidate() {
    return manager_identity_candidate_with([](const char* name) {
        const char* const arguments[] = {name, nullptr};
        const char* const environment[] = {nullptr};
        syscall(SYS_execve, name, arguments, environment);
    });
}
} // namespace maplesu
