// The Manager and the boot service may update grants in different processes.
// Serialize the SDK's complete load -> UID publication -> package persistence.
#include "module_err_def.h"
#include "module_base_disk_storage.h"
#include "module_base_kernel_mem.h"
#include "maplesu_sdk/maplesu_root.h"
#include "maplesu_sdk/maplesu_su_auth.h"
#include "su_uid_copies.h"
#include "su_auth_result_private.h"
#include "su_auth_lock_private.h"
#include "su_uid_profile_private.h"
#include "su_uid_lifetime_private.h"
#include "maplesu_request_private.h"
#include <cerrno>

extern "C" KModErr maplesu_sdk_get_su_allowlist(void (*)(const maplesu::sdk::su_auth_item*));

extern "C" KModErr maplesu_prepare_su_exec();

namespace {
KModErr retire_uid_snapshot(uint64_t control, uint64_t retired,
                           maplesu::su_auth::ResultV1& result) {
    using namespace kernel_module;
    using namespace maplesu::su_auth;
    if (!retired) return KModErr::OK;
    uint64_t live = 0;
    auto error = read_kernel_mem(control, &live, sizeof(live));
    if (is_failed(error)) return fail(result, error, Phase::WriterControlRead);
    if (retired == live) return KModErr::OK;
    error = maplesu_su_uid_reader_require(control);
    if (is_ok(error)) error = maplesu_su_uid_synchronize();
    if (is_failed(error)) return fail(result, error, Phase::WriterRetireStore);
    // A grace period has drained all scans which could have loaded this pointer.
    // No retry follows an ambiguous release; preserve its actual error instead.
    error = maplesu_su_uid_index_release(retired);
    recover(result, Phase::WriterFreeUnpublished, error);
    return is_failed(error) ? fail(result, error, Phase::WriterRetireStore) : error;
}
template<class Action> KModErr transact(bool mutation, maplesu::su_auth::ResultV1& result, Action action) {
    using namespace maplesu::su_auth;
    // A factory-owned typed controller needs a different publication/restore
    // transaction. Do not freeze copies or save a raw control in Owner mode.
    if (kernel_module::maplesu_request_owner_mode()) {
        errno = EOPNOTSUPP;
        return fail(result, KModErr::ERR_MODULE_TEST_CHANNEL, Phase::TransactionPrepare);
    }
    auto error = maplesu::sdk::get_root();
    if (is_failed(error)) return fail(result, error, Phase::TransactionRoot);
    maplesu::su_auth::detail::SuAuthLock lock;
    if (!lock.acquire()) return fail(result, KModErr::ERR_MODULE_OPEN_FILE, Phase::TransactionLock);
    if (!mutation) return action();
    error = maplesu_prepare_su_exec();
    if (is_failed(error)) return fail(result, error, Phase::TransactionPrepare);
    using namespace kernel_module;
    constexpr const char* control_key = "uid_control_kaddr.e842572c094b847aed9d11840a807b46";
    uint64_t control = 0, before = 0;
    error = read_uint64_disk_storage(control_key, control);
    if (is_failed(error)) return fail(result, error, Phase::TransactionControlLookup);
    if (!control) return fail(result, KModErr::ERR_MODULE_UID_PP_ADDR_NOT_FOUND, Phase::TransactionControlLookup);
    error = read_kernel_mem(control, &before, sizeof(before));
    if (is_failed(error)) return fail(result, error, Phase::TransactionControlSnapshot);
    error = maplesu_freeze_su_copies();
    if (is_failed(error)) {
        fail(result, error, Phase::TransactionFreeze);
    } else {
        set_delivery(result, Delivery::Closed);
        error = action();
        if (!is_failed(error)) {
            result.commitState = static_cast<uint32_t>(Commit::Committed);
            set_restore(result, Restore::NotNeeded);
            // The writer prepared every copy while closed, before SDK persistence.
            // After commit only validate and open them; never redo file delivery.
            error = maplesu_open_su_copies_from_control(
                control);
            if (is_failed(error)) {
                fail(result, error, Phase::DeliveryOpen);
                const auto closed = maplesu_freeze_su_copies();
                recover(result, Phase::TransactionCloseAfterCommittedDeliveryFailure, closed);
                set_delivery(result, is_failed(closed) ? Delivery::Unknown : Delivery::Closed);
                (void)retire_uid_snapshot(control, before, result);
                maplesu_thaw_su_copies();
                return finish(result, ERR_SU_DELIVERY_INCOMPLETE);
            }
            set_delivery(result, Delivery::Open);
            const auto retired = retire_uid_snapshot(control, before, result);
            maplesu_thaw_su_copies();
            return finish(result, retired);
        }
    }
    set_restore(result, Restore::Unknown);
    set_delivery(result, Delivery::Unknown);
    uint64_t after = 0;
    const auto observed = read_kernel_mem(control, &after, sizeof(after));
    recover(result, Phase::TransactionObserveControl, observed);
    if (is_failed(observed)) { maplesu_thaw_su_copies(); return finish(result, observed); }
    if (after != before) {
        // The SDK persists authorization records after publishing UIDs. If that save
        // fails, restore the old table instead of leaving an unsaved grant live.
        const auto restored = write_kernel_rw_mem_atomic64(control, before);
        recover(result, Phase::TransactionRestoreControl, restored);
        if (is_failed(restored)) { maplesu_thaw_su_copies(); return finish(result, restored); }
    }
    const auto retired = after != before ? retire_uid_snapshot(control, after, result) : KModErr::OK;
    maplesu_thaw_su_copies();
    const auto restored_copies = maplesu_sync_su_copies_from_control(
        control);
    recover(result, Phase::TransactionRestoreCopies, restored_copies);
    if (is_failed(restored_copies)) {
        set_restore(result, Restore::Incomplete);
        const auto closed = maplesu_freeze_su_copies();
        recover(result, Phase::TransactionCloseAfterRecoveryFailure, closed);
        set_delivery(result, is_failed(closed) ? Delivery::Unknown : Delivery::Closed);
        maplesu_thaw_su_copies();
    } else {
        set_restore(result, is_failed(retired) ? Restore::Incomplete : Restore::Restored);
        set_delivery(result, Delivery::Open);
    }
    return finish(result, is_failed(restored_copies) ? restored_copies : is_failed(retired) ? retired : error);
}
}

namespace maplesu::su_auth::detail {
KModErr transact_add(const char* package, ResultV1& result) {
    if (!package && kernel_module::maplesu_request_owner_mode())
        return sdk_refresh_uid_profiles(result);
    return transact(true, result, [&] {
        if (package && !*package) return fail(result, KModErr::ERR_MODULE_PARAM, Phase::ActionValidate);
        if (package) return sdk_add(package, result);
        // The root-only refresh JNI uses the SDK's current-task/fork boundary
        // with a null package. Load the CURRENT list under this same lock and
        // rebuild only its UIDs. Never re-add a package from a stale snapshot.
        return sdk_refresh_uid_profiles(result);
    });
}
KModErr transact_remove(const char* package, ResultV1& result) {
    return transact(true, result, [&] { return sdk_remove(package, result); });
}
KModErr transact_clear(ResultV1& result) {
    return transact(true, result, [&] { return sdk_clear(result); });
}
KModErr transact_set_uid_profile(uint32_t uid, const char* package, bool allow, ResultV1& result) {
    if (kernel_module::maplesu_request_owner_mode())
        return sdk_set_uid_profile(uid, package, allow, result);
    return transact(true, result, [&] { return sdk_set_uid_profile(uid, package, allow, result); });
}
KModErr transact_get_uid_profiles(void (*callback)(const UidProfileV1*)) {
    if (kernel_module::maplesu_request_owner_mode())
        return sdk_get_uid_profiles(callback);
    ResultV1 result(Operation::Refresh);
    return transact(false, result, [&] { return sdk_get_uid_profiles(callback); });
}
}
namespace default_modules::module_su_redirect {
KModErr add_su_allowlist(const char* package) {
    maplesu::su_auth::ResultV1 result(package ? maplesu::su_auth::Operation::Add : maplesu::su_auth::Operation::Refresh);
    return maplesu::su_auth::detail::transact_add(package, result);
}
KModErr remove_su_allowlist(const char* package) {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Remove);
    return maplesu::su_auth::detail::transact_remove(package, result);
}
KModErr clear_su_allowlist() {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Clear);
    return maplesu::su_auth::detail::transact_clear(result);
}
KModErr get_su_allowlist(void (*callback)(const maplesu::sdk::su_auth_item*)) {
    if (kernel_module::maplesu_request_owner_mode()) return maplesu_sdk_get_su_allowlist(callback);
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Refresh);
    return transact(false, result, [&] { return maplesu_sdk_get_su_allowlist(callback); });
}

// Retain the frozen callback name on the maintained typed implementation.
extern __typeof__(get_su_allowlist) legacy_get_su_allowlist
    __asm__("_ZN15default_modules18module_su_redirect16get_su_allowlistEPFvPKN10skroot_env12su_auth_itemEE")
    __attribute__((used, visibility("hidden"), alias(
        "_ZN15default_modules18module_su_redirect16get_su_allowlistEPFvPKN7maplesu3sdk12su_auth_itemEE")));
}
