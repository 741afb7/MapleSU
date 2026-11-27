// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include "../../maplesu-sdk/sdk/include/maplesu_request_private.h"
#include <array>
#include <cstring>

extern "C" const char* __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
extern "C" void __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(const char*);

namespace maplesu {

inline bool& bridge_legacy_scope() { static thread_local bool selected = false; return selected; }
inline kernel_module::RequestAuthMode bridge_request_auth_mode() {
    return bridge_legacy_scope() ? kernel_module::RequestAuthMode::LegacyKey :
        kernel_module::RequestAuthMode::ManagerOwner;
}

/** Only the synchronous Java upcall selects Legacy; ordinary entries select Owner. */
class __attribute__((visibility("hidden"))) LegacyCredentialScope final {
public:
    explicit LegacyCredentialScope(const char* credential) {
        using namespace kernel_module;
        const auto state = maplesu_request_auth_state();
        if (!maplesu_request_auth_usable(state)) {
            error_ = KModErr::ERR_MODULE_TEST_CHANNEL;
            return;
        }
        previous_ = maplesu_request_auth_mode(state);
        previous_selected_ = bridge_legacy_scope();
        if (previous_ == RequestAuthMode::LegacyKey) {
            const char* previous = __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
            if (previous) std::strncpy(previous_key_.data(), previous, previous_key_.size() - 1);
        }
        error_ = maplesu_request_select_legacy(credential);
        active_ = !is_failed(error_);
        if (active_) bridge_legacy_scope() = true;
    }
    ~LegacyCredentialScope() { (void)restore(); erase(previous_key_.data(), previous_key_.size()); }
    LegacyCredentialScope(const LegacyCredentialScope&) = delete;
    LegacyCredentialScope& operator=(const LegacyCredentialScope&) = delete;
    KModErr error() const { return error_; }
    KModErr restore() {
        if (!active_) return KModErr::OK;
        active_ = false;
        kernel_module::CleanupReceiptScope diagnostics;
        close_error_ = kernel_module::maplesu_request_owner_close();
        kernel_module::maplesu_request_clear_legacy();
        const auto restored = kernel_module::maplesu_request_set_auth_mode(previous_);
        if (!is_failed(restored) && previous_ == kernel_module::RequestAuthMode::LegacyKey)
            __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(previous_key_.data());
        bridge_legacy_scope() = previous_selected_;
        erase(previous_key_.data(), previous_key_.size());
        return is_failed(close_error_) ? close_error_ : restored;
    }
    KModErr close_error() const { return close_error_; }
    static void erase(char* bytes, size_t size) {
        volatile char* cursor = bytes;
        while (size--) *cursor++ = 0;
    }

private:
    std::array<char, 1024> previous_key_{};
    kernel_module::RequestAuthMode previous_ = kernel_module::RequestAuthMode::ManagerOwner;
    KModErr error_ = KModErr::OK, close_error_ = KModErr::OK;
    bool active_ = false, previous_selected_ = false;
};

class __attribute__((visibility("hidden"))) RequestAuthModeScope final {
public:
    explicit RequestAuthModeScope(kernel_module::RequestAuthMode requested) {
        using namespace kernel_module;
        const auto state = maplesu_request_auth_state();
        if (!maplesu_request_auth_usable(state)) {
            error_ = KModErr::ERR_MODULE_TEST_CHANNEL;
            return;
        }
        previous_ = maplesu_request_auth_mode(state);
        if (previous_ == requested) return;
        error_ = maplesu_request_set_auth_mode(requested);
        changed_ = !is_failed(error_);
    }
    ~RequestAuthModeScope() { (void)restore(); }
    RequestAuthModeScope(const RequestAuthModeScope&) = delete;
    RequestAuthModeScope& operator=(const RequestAuthModeScope&) = delete;
    KModErr error() const { return error_; }
    KModErr restore() {
        if (!changed_) return KModErr::OK;
        changed_ = false;
        kernel_module::CleanupReceiptScope diagnostic;
        return kernel_module::maplesu_request_set_auth_mode(previous_);
    }

private:
    kernel_module::RequestAuthMode previous_ = kernel_module::RequestAuthMode::ManagerOwner;
    KModErr error_ = KModErr::OK;
    bool changed_ = false;
};

struct ManagerOwnerIdentity {
    KModErr error = KModErr::OK;
    uint32_t full_uid = 0;
    uint32_t epoch = 0;
    maplesu_host_instance host{};
};

inline KModErr manager_worker_admission() {
    using namespace kernel_module;
    RequestAuthModeScope mode(RequestAuthMode::ManagerOwner);
    if (is_failed(mode.error())) return mode.error();
    const auto admission = maplesu_request_worker_admission();
    const auto restored = mode.restore();
    return is_failed(admission) ? admission : restored;
}

inline ManagerOwnerIdentity query_manager_owner_identity() {
    using namespace kernel_module;
    RequestAuthModeScope mode(RequestAuthMode::ManagerOwner);
    ManagerOwnerIdentity result;
    result.error = mode.error();
    if (is_failed(result.error)) return result;
    maplesu_metadata_owner_v3 metadata{};
    uint64_t descriptor = 0;
    RequestOwnerBinding binding{};
    result.error = maplesu_request_owner_channel(metadata, descriptor, binding);
    const auto restore = mode.restore();
    if (is_failed(result.error)) return result;
    if (is_failed(restore)) {
        result.error = restore;
        return result;
    }
    result.full_uid = metadata.owner_full_uid;
    result.epoch = metadata.owner_epoch;
    result.host = metadata.host;
    return result;
}

} // namespace maplesu
