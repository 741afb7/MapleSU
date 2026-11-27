// The Manager and the boot service may update grants in different processes.
// Serialize the SDK's complete load -> UID publication -> package persistence.
#include "module_err_def.h"
#include "module_base_disk_storage.h"
#include "module_base_kernel_mem.h"
#include "maplesu_sdk/maplesu_root.h"
#include "maplesu_sdk/maplesu_su_auth.h"
#include "su_uid_copies.h"
#include "su_auth_result_private.h"
#include <cerrno>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef MAPLESU_SU_AUTH_LOCK_DIR
#define MAPLESU_SU_AUTH_LOCK_DIR "/data/adb/maplesu"
#endif

extern "C" KModErr maplesu_sdk_get_su_allowlist(void (*)(const maplesu::sdk::su_auth_item*));

extern "C" KModErr maplesu_prepare_su_exec();

namespace {
thread_local std::vector<std::string> refresh_packages;
void collect_refresh_package(const maplesu::sdk::su_auth_item* item) {
    refresh_packages.emplace_back(item->app_package_name);
}

struct SuAuthLock {
    int fd = -1;
    bool acquire() {
        // /data/adb may be absent in an SDK-only installation.
        if (mkdir("/data/adb", 0700) != 0 && errno != EEXIST) return false;
        if (mkdir(MAPLESU_SU_AUTH_LOCK_DIR, 0700) != 0 && errno != EEXIST) return false;
        const int directory = open(MAPLESU_SU_AUTH_LOCK_DIR, O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (directory < 0) return false;
        struct stat st{};
        if (fstat(directory, &st) == 0 && st.st_uid == 0 && (st.st_mode & 0022) == 0)
            fd = openat(directory, "ultra-su-auth.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
        close(directory);
        if (fd < 0 || fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != 0 ||
            st.st_nlink != 1 || (st.st_mode & 0077) != 0) return false;
        int result;
        do { result = flock(fd, LOCK_EX); } while (result != 0 && errno == EINTR);
        return result == 0;
    }
    ~SuAuthLock() { if (fd >= 0) close(fd); }
};

template<class Action> KModErr transact(bool mutation, maplesu::su_auth::ResultV1& result, Action action) {
    using namespace maplesu::su_auth;
    auto error = maplesu::sdk::get_root();
    if (is_failed(error)) return fail(result, error, Phase::TransactionRoot);
    SuAuthLock lock;
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
                maplesu_thaw_su_copies();
                return finish(result, ERR_SU_DELIVERY_INCOMPLETE);
            }
            set_delivery(result, Delivery::Open);
            maplesu_thaw_su_copies();
            return finish(result, KModErr::OK);
        }
    }
    set_restore(result, Restore::Unknown);
    set_delivery(result, Delivery::Unknown);
    uint64_t after = 0;
    const auto observed = read_kernel_mem(control, &after, sizeof(after));
    recover(result, Phase::TransactionObserveControl, observed);
    if (is_failed(observed)) { maplesu_thaw_su_copies(); return finish(result, observed); }
    if (after != before) {
        // The SDK persists package names after publishing UIDs. If that save
        // fails, restore the old table instead of leaving an unsaved grant live.
        const auto restored = write_kernel_rw_mem_atomic64(control, before);
        recover(result, Phase::TransactionRestoreControl, restored);
        if (is_failed(restored)) { maplesu_thaw_su_copies(); return finish(result, restored); }
        // Both tables have been published. Retain both until reboot; a reader
        // can still hold `after` even though persistence forced a rollback.
    }
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
        set_restore(result, Restore::Restored);
        set_delivery(result, Delivery::Open);
    }
    return finish(result, is_failed(restored_copies) ? restored_copies : error);
}
}

namespace maplesu::su_auth::detail {
KModErr transact_add(const char* package, ResultV1& result) {
    return transact(true, result, [&] {
        if (package && !*package) return fail(result, KModErr::ERR_MODULE_PARAM, Phase::ActionValidate);
        if (package) return sdk_add(package, result);
        // The root-only refresh JNI uses the SDK's current-task/fork boundary
        // with a null package. Load the CURRENT list under this same lock and
        // rebuild only its UIDs. Never re-add a package from a stale snapshot.
        refresh_packages.clear();
        const auto error = maplesu_sdk_get_su_allowlist(collect_refresh_package);
        if (is_failed(error)) return fail(result, error, Phase::ActionRefreshList);
        return write_allowlist(refresh_packages, nullptr, true, result);
    });
}
KModErr transact_remove(const char* package, ResultV1& result) {
    return transact(true, result, [&] { return sdk_remove(package, result); });
}
KModErr transact_clear(ResultV1& result) {
    return transact(true, result, [&] { return sdk_clear(result); });
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
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Refresh);
    return transact(false, result, [&] { return maplesu_sdk_get_su_allowlist(callback); });
}
}
