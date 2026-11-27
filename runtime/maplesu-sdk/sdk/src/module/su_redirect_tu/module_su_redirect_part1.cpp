// module su redirect implementation.
//
// The module redirects the kernel side of exec/stat/access so that an allowed
// process resolves the private fake "su" file while every other process keeps
// the real system view. Legacy package entries and full-UID decisions share one
// persisted list; the matching kernel UID table is published through the kit.
#include "module_su_redirect_private.h"
#include "su_auth_lock_private.h"
#include "su_uid_profile_private.h"
#include "su_uid_copies.h"
#include "module_base_disk_storage.h"
#include "module_base_kernel_mem.h"
#include "maplesu_request_private.h"
#include "su_owner_runtime_private.h"
#include "su_compat_hooks.h"
#include "private_module_su_patch.h"
#include "su_uid_lifetime_private.h"
#include "module_base_kernel_request.h"
#include "maplesu_hook_private.h"
#include <cerrno>

namespace default_modules {
namespace module_su_redirect {

namespace {

// The module keeps one table instance for its whole lifetime.
constexpr char kUidControlStorageKey[] =
    "uid_control_kaddr.e842572c094b847aed9d11840a807b46";
constexpr char kNextFreeStorageKey[] = "next_free_kaddr.3c6b48e93b504ddadad9f9bbb7f732fc";
constexpr uint32_t kUidControlSize = sizeof(uint64_t);

}  // namespace

// Remembers where the hook reads its UID control word from. The boot service
// resets the slot before the hooks are installed so a stale address from a
// previous boot is never dereferenced.
static KModErr save_uid_control_kaddr(uint64_t kaddr) {
    return kernel_module::write_uint64_disk_storage(kUidControlStorageKey, kaddr);
}

// Only an unexposed or completely retired Hook group permits freeing its raw
// UID state. Keep the primary failure/receipt separate from cleanup failures.
class SuUidBootstrapGuard {
public:
    SuUidBootstrapGuard(SuPatchBatchGuard& group, uint64_t& control)
        : group_(group), control_(control) {}
    void initialized() noexcept { initialized_ = true; }
    KModErr finish(KModErr primary) {
        const auto result = group_.finish(primary);
        finished_ = true;
        if (is_failed(result)) {
            const kernel_module::HookCleanupReceiptScope preserve_receipt;
            try { cleanup(); }
            catch (...) { std::printf("SU UID cleanup: exception\n"); }
        }
        return result;
    }
    ~SuUidBootstrapGuard() {
        if (!finished_) {
            try { (void)finish(KModErr::ERR_MODULE_ASM); } catch (...) {}
        }
    }
private:
    void cleanup() {
        if (!control_) return;
        const auto error = maplesu_su_uid_bootstrap_retire(
            control_, initialized_, group_.readers_retired());
        if (is_failed(error)) std::printf("SU UID cleanup: %lld\n", static_cast<long long>(error));
        // Single-shot ownership: an uncertain native release must not be retried.
        control_ = 0;
    }
    SuPatchBatchGuard& group_;
    uint64_t& control_;
    bool initialized_ = false;
    bool finished_ = false;
};

// Publishes `packages` into the kernel view through the shared list writer.
static KModErr write_allowlist(const std::vector<std::string>& packages) {
    return maplesu_write_su_allowlist(packages, nullptr, false);
}

KModErr init(const char* fake_su_path, bool print_only) {
    // The boot factory owns the one typed controller and native hook set.
    // Owner/helper callers only observe it: no raw UID allocation, hook install,
    // source refresh, namespace-as-readiness or implicit Legacy fallback.
    if (kernel_module::maplesu_request_owner_mode()) {
        kernel_module::RequestSuRuntimeStatus observed{};
        const auto error = kernel_module::maplesu_request_owner_su_runtime(observed);
        const int original_errno = errno;
        if (print_only && observed.record.schema == MAPLESU_SU_RUNTIME_STATUS_SCHEMA &&
            observed.record.bytes == sizeof(observed.record)) {
            printf("native SU runtime: flags=0x%x hooks=%u target=%u status=%d "
                   "target_error=%d cleanup_error=%d start_error=%d\n",
                   observed.record.flags, observed.record.hook_count,
                   observed.record.target_qualified, observed.record.status,
                   observed.record.target_error, observed.record.cleanup_error,
                   observed.record.start_error);
        }
        errno = original_errno;
        if (is_failed(error)) return error; // Keep the same completed CORE receipt.
        if (!kernel_module::maplesu_request_owner_binding_matches(observed.binding)) {
            errno = ESTALE;
            return KModErr::ERR_MODULE_ROOT_KEY;
        }
        const auto& status = observed.record;
        if (status.status < 0) {
            errno = -status.status;
            return KModErr::ERR_MODULE_TEST_CHANNEL;
        }
        if (status.target_qualified != 1) {
            errno = EOPNOTSUPP;
            return KModErr::ERR_MODULE_TEST_CHANNEL;
        }
        constexpr uint32_t active = MAPLESU_SU_RUNTIME_INITIALIZED | MAPLESU_SU_RUNTIME_BOUND |
            MAPLESU_SU_RUNTIME_STARTED | MAPLESU_SU_RUNTIME_PUBLISHED;
        bool generation = false;
        for (const auto byte : status.runtime_generation) generation = generation || byte != 0;
        if (status.schema != MAPLESU_SU_RUNTIME_STATUS_SCHEMA || status.bytes != sizeof(status) ||
            status.status || status.target_error || status.start_error || status.flags != active ||
            status.hook_count < 3 || status.hook_count > MAPLESU_SU_RUNTIME_MAX_HOOKS ||
            status.installation_flags || !generation ||
            !(status.host.low | status.host.high) ||
            status.host.low != observed.binding.host.low || status.host.high != observed.binding.host.high ||
            memcmp(status.installation_generation, status.runtime_generation, 16)) {
            errno = ESTALE;
            return KModErr::ERR_MODULE_TEST_CHANNEL;
        }
        return KModErr::OK; // Factory state observed, not app authorization or device SU proof.
    }
    maplesu::su_auth::detail::SuAuthLock lock;
    KModErr error = KModErr::OK;
    uint64_t uid_control_kaddr = 0;

    uint32_t task_cred_offset = 0;
    uint32_t cred_uid_offset = 0;
    uint32_t cred_euid_offset = 0;
    KernelSymbolOffset offset{};

    SuPatchBatchGuard hook_group;
    SuUidBootstrapGuard uid_state(hook_group, uid_control_kaddr);
    const auto finish = [&uid_state](KModErr result) {
        result = uid_state.finish(result);
        const int original_errno = errno;
        printf("patch_kernel_handler ret: %s\n", to_string(result).c_str());
        errno = original_errno;
        return result;
    };

    error = kernel_module::get_task_struct_cred_offset(task_cred_offset);
    if (is_failed(error)) {
        return finish(error);
    }
    printf("cred offset: 0x%x\n", task_cred_offset);
    error = kernel_module::get_cred_uid_offset(cred_uid_offset);
    if (is_failed(error)) {
        return finish(error);
    }
    error = kernel_module::get_cred_euid_offset(cred_euid_offset);
    if (is_failed(error)) {
        return finish(error);
    }
    printf("cred uid offset: 0x%x\n", cred_uid_offset);

    error = kernel_module::get_do_execve_info(offset.execve.kaddr, offset.execve.filename_reg,
                                              offset.execve.single_ptr);
    if (is_failed(error)) {
        return finish(error);
    }
    printf("do_execve addr: %p, filename reg:%d, single ptr:%d\n",
           reinterpret_cast<void*>(offset.execve.kaddr), static_cast<int>(offset.execve.filename_reg),
           static_cast<int>(offset.execve.single_ptr));

    error = kernel_module::get_syscall_func_kaddr(__NR_newfstatat, offset.sys_newfstatat_kaddr);
    if (is_failed(error)) {
        return finish(error);
    }
    printf("sys_newfstatat addr: %p\n",
           reinterpret_cast<void*>(offset.sys_newfstatat_kaddr));

    if (syscall_probe::has_statx()) {
        error = kernel_module::get_syscall_func_kaddr(__NR_statx, offset.sys_statx_kaddr);
        if (is_failed(error)) {
            return finish(error);
        }
        printf("sys_statx addr: %p\n", reinterpret_cast<void*>(offset.sys_statx_kaddr));
    }

    error = kernel_module::get_syscall_func_kaddr(__NR_faccessat, offset.sys_faccessat_kaddr);
    if (is_failed(error)) {
        return finish(error);
    }
    printf("sys_faccessat addr: %p\n", reinterpret_cast<void*>(offset.sys_faccessat_kaddr));

    if (syscall_probe::has_faccessat2()) {
        error = kernel_module::get_syscall_func_kaddr(__NR_faccessat2,
                                                      offset.sys_faccessat2_kaddr);
        if (is_failed(error)) {
            return finish(error);
        }
        printf("sys_faccessat2 addr: %p\n",
               reinterpret_cast<void*>(offset.sys_faccessat2_kaddr));
    }

    CompatSuHooks compat;
    error = resolve_compat_su_hooks(offset, compat);
    if (is_failed(error)) return finish(error);

    // The print-only variant reports the resolved layout and stops here; it is
    // used by the manager to show what this kernel would be patched with.
    if (print_only) {
        return finish(KModErr::OK);
    }

    if (!print_only) {
        if (!lock.acquire()) return KModErr::ERR_MODULE_OPEN_FILE;
        error = maplesu_su_uid_reader_reset();
        if (is_failed(error)) return finish(error);
        // The control block is rebuilt from scratch on every boot; whatever an
        // earlier boot stored is not valid any more.
        error = save_uid_control_kaddr(0);
        if (is_failed(error)) {
            return finish(error);
        }
        error = kernel_module::write_uint64_disk_storage(kNextFreeStorageKey, 0);
        if (is_failed(error)) {
            return finish(error);
        }

        if (!g_suAllowList.load()) {
            puts("loading su allowlist file failed");
            return finish(KModErr::ERR_MODULE_OPEN_FILE);
        }

        // 64-bit control word the hooks read to decide whether the current process
        // is allowed to see the fake su file. Publishing it replaces the previous
        // table atomically, so allocate and clear the replacement first.
        error = kernel_module::request_kernel_alloc_mem(kUidControlSize, uid_control_kaddr);
        if (is_failed(error)) {
            return finish(error);
        }
        error = kernel_module::fill00_kernel_mem(uid_control_kaddr, kUidControlSize);
        if (is_failed(error)) {
            return finish(error);
        }
        uid_state.initialized();
        error = save_uid_control_kaddr(uid_control_kaddr);
        if (is_failed(error)) {
            return finish(error);
        }

        std::vector<std::string> packages = g_suAllowList.get_apps();
        error = write_allowlist(packages);
        if (is_failed(error)) {
            return finish(error);
        }
    }

    error = hook_group.begin();
    if (is_failed(error)) return finish(error);
    {
        PatchBase base(task_cred_offset, cred_uid_offset, cred_euid_offset, fake_su_path);
        PatchDoExecve do_execve_patch(base, offset.execve);
        PatchSysNewfstatat sys_newfstatat_patch(base, offset.sys_newfstatat_kaddr);
        PatchSysStatx sys_statx_patch(base, offset.sys_statx_kaddr);
        PatchSysFaccessat sys_faccessat_patch(base, offset.sys_faccessat_kaddr);
        PatchSysFaccessat2 sys_faccessat2_patch(base, offset.sys_faccessat2_kaddr);

        error = do_execve_patch.patch_do_execve(uid_control_kaddr);
        printf("patch do_execve ret: %s\n", to_string(error).c_str());
        if (is_failed(error)) {
            return finish(error);
        }

        error = sys_newfstatat_patch.patch_sys_newfstatat(uid_control_kaddr);
        printf("patch sys_newfstatat ret: %s\n", to_string(error).c_str());
        if (is_failed(error)) {
            return finish(error);
        }

        if (offset.sys_statx_kaddr != 0) {
            error = sys_statx_patch.patch_sys_statx(uid_control_kaddr);
            printf("patch sys_statx ret: %s\n", to_string(error).c_str());
            if (is_failed(error)) {
                return finish(error);
            }
        }

        error = sys_faccessat_patch.patch_sys_faccessat(uid_control_kaddr);
        printf("patch sys_faccessat ret: %s\n", to_string(error).c_str());
        if (is_failed(error)) {
            return finish(error);
        }

        if (offset.sys_faccessat2_kaddr != 0 && !compat.native_access_alias) {
            error = sys_faccessat2_patch.patch_sys_faccessat2(uid_control_kaddr);
            printf("patch sys_faccessat2 ret: %s\n", to_string(error).c_str());
            if (is_failed(error)) {
                return finish(error);
            }
        }
    }

    if (is_ok(error)) {
        PatchBase base(task_cred_offset, cred_uid_offset, cred_euid_offset, fake_su_path);
        error = install_compat_su_hooks(base, compat, uid_control_kaddr);
    }
    if (is_ok(error)) error = maplesu_su_uid_reader_activate(uid_control_kaddr);
    return finish(error);
}

const char* get_module_eng_name() {
    return "module_su_redirect";
}

}  // namespace module_su_redirect
}  // namespace default_modules

// SDK level allowlist entry points used by the transaction layer. They keep
// the kit's original load -> publish -> persist order, minus the cross process
// locking that the transaction layer adds around them.
extern "C" KModErr maplesu_sdk_get_su_allowlist(void (*callback)(const maplesu::sdk::su_auth_item*)) {
    if (!callback) return KModErr::ERR_MODULE_PARAM;
    if (kernel_module::maplesu_request_owner_mode()) {
        maplesu_uid_metadata_v3 metadata{};
        std::vector<maplesu_uid_record_v3> rows;
        const auto read = maplesu::su_auth::detail::owner_runtime_rows(0, metadata, rows);
        if (is_failed(read)) return read;
        for (const auto& row : rows)
            if (!maplesu::su_auth::detail::owner_runtime_package_valid(row.package))
                return KModErr::ERR_MODULE_TEST_CHANNEL;
        for (const auto& row : rows) {
            if (!row.decision || row.full_uid >= 100000) continue;
            maplesu::sdk::su_auth_item item{};
            std::memcpy(item.app_package_name, row.package, sizeof(item.app_package_name));
            callback(&item);
        }
        return KModErr::OK;
    }
    const auto error = maplesu::sdk::get_root();
    if (is_failed(error)) {
        return error;
    }
    if (!default_modules::module_su_redirect::g_suAllowList.load()) {
        return KModErr::ERR_MODULE_OPEN_FILE;
    }

    const auto packages = default_modules::module_su_redirect::g_suAllowList.get_apps();
    std::vector<maplesu::su_auth::UidProfileV1> profiles;
    const auto collected = maplesu::su_auth::detail::collect_uid_profiles(packages, profiles);
    if (is_failed(collected)) return collected;
    for (const auto& profile : profiles) {
        // The old package-only ABI describes user 0 grants. Keep explicit
        // multi-user decisions in the same store without exposing raw markers.
        if (!profile.allow || profile.uid >= 100000) continue;
        maplesu::sdk::su_auth_item item{};
        std::memcpy(item.app_package_name, profile.package, sizeof(item.app_package_name));
        callback(&item);
    }
    return KModErr::OK;
}

KModErr maplesu::su_auth::detail::sdk_add(const char* package, ResultV1& result) {
    KModErr error = maplesu::sdk::get_root();
    if (is_failed(error)) {
        return fail(result, error, Phase::ActionRoot);
    }
    if (!default_modules::module_su_redirect::g_suAllowList.load()) {
        return fail(result, KModErr::ERR_MODULE_OPEN_FILE, Phase::ActionLoad);
    }

    std::vector<std::string> packages = default_modules::module_su_redirect::g_suAllowList.get_apps();
    error = replace_legacy_uid_profile(packages, package, true);
    if (is_failed(error)) return fail(result, error, Phase::ActionPackage);

    error = write_allowlist(packages, package, false, result);
    if (is_failed(error)) {
        return error;
    }
    std::vector<UidProfileV1> delivered;
    error = collect_uid_profiles(packages, delivered);
    if (is_failed(error)) return fail(result, error, Phase::ActionPackage);
    if (std::none_of(delivered.begin(), delivered.end(), [&](const auto& profile) {
            return profile.allow && profile.uid < 100000 && strcmp(profile.package, package) == 0;
        })) return fail(result, KModErr::ERR_MODULE_SU_LIST_NOT_FOUND, Phase::ActionPackage);
    if (!default_modules::module_su_redirect::g_suAllowList.set_app_list(packages))
        return fail(result, KModErr::ERR_MODULE_WRITE_FILE, Phase::ActionPersist);
    result.commitState = static_cast<uint32_t>(Commit::Committed);
    return finish(result, KModErr::OK);
}

KModErr maplesu::su_auth::detail::sdk_remove(const char* package, ResultV1& result) {
    KModErr error = maplesu::sdk::get_root();
    if (is_failed(error)) {
        return fail(result, error, Phase::ActionRoot);
    }
    if (!default_modules::module_su_redirect::g_suAllowList.load()) {
        return fail(result, KModErr::ERR_MODULE_OPEN_FILE, Phase::ActionLoad);
    }

    std::vector<std::string> packages = default_modules::module_su_redirect::g_suAllowList.get_apps();
    error = replace_legacy_uid_profile(packages, package, false);
    if (is_failed(error)) return fail(result, error, Phase::ActionPackage);

    error = write_allowlist(packages, nullptr, false, result);
    if (is_failed(error)) {
        return error;
    }
    if (!default_modules::module_su_redirect::g_suAllowList.set_app_list(packages))
        return fail(result, KModErr::ERR_MODULE_WRITE_FILE, Phase::ActionPersist);
    result.commitState = static_cast<uint32_t>(Commit::Committed);
    return finish(result, KModErr::OK);
}

KModErr maplesu::su_auth::detail::sdk_clear(ResultV1& result) {
    KModErr error = maplesu::sdk::get_root();
    if (is_failed(error)) {
        return fail(result, error, Phase::ActionRoot);
    }
    if (!default_modules::module_su_redirect::g_suAllowList.load()) {
        return fail(result, KModErr::ERR_MODULE_OPEN_FILE, Phase::ActionLoad);
    }

    const std::vector<std::string> packages;
    error = write_allowlist(packages, nullptr, false, result);
    if (is_failed(error)) {
        return error;
    }
    if (!default_modules::module_su_redirect::g_suAllowList.clear_app())
        return fail(result, KModErr::ERR_MODULE_WRITE_FILE, Phase::ActionPersist);
    result.commitState = static_cast<uint32_t>(Commit::Committed);
    return finish(result, KModErr::OK);
}

KModErr maplesu::su_auth::detail::sdk_set_uid_profile(
    uint32_t uid, const char* package, bool allow, ResultV1& result) {
    if (kernel_module::maplesu_request_owner_mode()) {
        if (uid > uint32_t(INT32_MAX) || (uid != 1000 && uid < 2000) ||
            !owner_runtime_package_valid(package)) {
            errno = EINVAL;
            return fail(result, KModErr::ERR_MODULE_PARAM, Phase::ActionValidate);
        }
        return owner_runtime_sync(uid, package, allow, result);
    }
    auto error = maplesu::sdk::get_root();
    if (is_failed(error)) return fail(result, error, Phase::ActionRoot);
    if (!default_modules::module_su_redirect::g_suAllowList.load())
        return fail(result, KModErr::ERR_MODULE_OPEN_FILE, Phase::ActionLoad);
    auto packages = default_modules::module_su_redirect::g_suAllowList.get_apps();
    error = replace_uid_profile(packages, uid, package, allow);
    if (is_failed(error)) return fail(result, error, Phase::ActionPackage);
    error = write_allowlist(packages, nullptr, false, result);
    if (is_failed(error)) return error;
    if (allow) {
        std::vector<UidProfileV1> delivered;
        error = collect_uid_profiles(packages, delivered);
        if (is_failed(error)) return fail(result, error, Phase::ActionPackage);
        if (std::none_of(delivered.begin(), delivered.end(), [&](const auto& profile) {
                return profile.uid == uid && profile.allow;
            })) return fail(result, KModErr::ERR_MODULE_SU_LIST_NOT_FOUND, Phase::ActionPackage);
    }
    if (!default_modules::module_su_redirect::g_suAllowList.set_app_list(packages))
        return fail(result, KModErr::ERR_MODULE_WRITE_FILE, Phase::ActionPersist);
    result.commitState = static_cast<uint32_t>(Commit::Committed);
    return finish(result, KModErr::OK);
}

KModErr maplesu::su_auth::detail::sdk_get_uid_profiles(void (*callback)(const UidProfileV1*)) {
    if (kernel_module::maplesu_request_owner_mode()) {
        return owner_runtime_profiles(callback);
    }
    const auto error = maplesu::sdk::get_root();
    if (is_failed(error)) return error;
    if (!default_modules::module_su_redirect::g_suAllowList.load())
        return KModErr::ERR_MODULE_OPEN_FILE;
    std::vector<UidProfileV1> profiles;
    const auto collected = collect_uid_profiles(
        default_modules::module_su_redirect::g_suAllowList.get_apps(), profiles);
    if (is_failed(collected)) return collected;
    // Reading the stored decisions does not claim that native SU delivery is
    // ready. The collector retains installation-bound grants and explicit denies;
    // mutation transactions independently prepare and verify their delivery.
    for (const auto& profile : profiles) callback(&profile);
    return KModErr::OK;
}

KModErr maplesu::su_auth::detail::sdk_refresh_uid_profiles(ResultV1& result) {
    if (kernel_module::maplesu_request_owner_mode()) {
        // Request the complete durable authority through the boot-owned provider,
        // including newly saved full UIDs and retained revocation tombstones.
        return owner_runtime_sync(0, nullptr, false, result);
    }
    const auto error = maplesu::sdk::get_root();
    if (is_failed(error)) return fail(result, error, Phase::ActionRoot);
    if (!default_modules::module_su_redirect::g_suAllowList.load())
        return fail(result, KModErr::ERR_MODULE_OPEN_FILE, Phase::ActionRefreshList);
    // Refresh the complete current authority, including explicit denies and
    // secondary-user UIDs; a package-only projection would discard both.
    return write_allowlist(default_modules::module_su_redirect::g_suAllowList.get_apps(),
                           nullptr, true, result);
}

extern "C" KModErr maplesu_sdk_add_su_allowlist(const char* package) {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Add);
    return maplesu::su_auth::detail::sdk_add(package, result);
}
extern "C" KModErr maplesu_sdk_remove_su_allowlist(const char* package) {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Remove);
    return maplesu::su_auth::detail::sdk_remove(package, result);
}
extern "C" KModErr maplesu_sdk_clear_su_allowlist() {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Clear);
    return maplesu::su_auth::detail::sdk_clear(result);
}

namespace default_modules {
namespace module_su_redirect {

SuAllowList g_suAllowList;

}  // namespace module_su_redirect
}  // namespace default_modules

namespace android_pkgmap {

std::unordered_map<std::string, uint32_t> read_pkg_uids_from_packages_list(
    const std::vector<std::string>& packages, const std::string& packages_list_path) {
    std::unordered_map<std::string, uint32_t> package_uids;
    package_uids.reserve(packages.size());
    for (const auto& package : packages) {
        package_uids.emplace(package, 0);
    }

    std::unordered_set<std::string> pending(packages.begin(), packages.end());
    size_t remaining = pending.size();
    if (remaining == 0) {
        return package_uids;
    }

    std::ifstream input(packages_list_path);
    if (!input.is_open()) {
        return package_uids;
    }

    std::string line;
    std::string package;
    uint32_t uid = 0;
    while (std::getline(input, line)) {
        if (!detail::parse_line_pkg_uid(line, package, uid, nullptr)) {
            continue;
        }
        const auto found = pending.find(package);
        if (found == pending.end()) {
            continue;
        }
        package_uids[package] = uid;
        pending.erase(found);
        if (--remaining == 0) {
            break;
        }
    }
    return package_uids;
}

}  // namespace android_pkgmap
