// The SDK's add/init/remove/clear paths share this writer. Keep the live UID
// pointer intact until its replacement has been resolved, written and verified.
#include "module_err_def.h"
#include "module_base_disk_storage.h"
#include "module_base_kernel_mem.h"
#include "su_retention_store.h"
#include "su_uid_copies.h"
#include "su_auth_result_private.h"
extern "C" KModErr maplesu_prepare_su_exec();
#include <algorithm>
#include <charconv>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace kernel_module {
// The SDK exports the callback ABI; its vector overload is only an inline helper.
KModErr read_blob_disk_storage_with_cb(const char*, void (*)(const void*, uint64_t));
}

namespace android_pkgmap {
std::unordered_map<std::string, uint32_t> read_pkg_uids_from_packages_list(
    const std::vector<std::string>& packages, const std::string& path);
}

namespace {
constexpr const char* control_key = "uid_control_kaddr.e842572c094b847aed9d11840a807b46";
constexpr const char* retired_key = "next_free_kaddr.3c6b48e93b504ddadad9f9bbb7f732fc";
constexpr const char* retention_key = "uid_retention_budget.20261105";

// The installed hook has no reader critical section. Published snapshots therefore
// live until reboot, including a snapshot replaced by transaction rollback. Reserve
// a durable, boot-scoped budget BEFORE allocation/publication; crashes may consume
// budget, but cannot create unaccounted published allocations.
KModErr reserve_snapshot(uint32_t bytes) {
    using namespace kernel_module;
    char boot[38]{};
    FILE* input = fopen("/proc/sys/kernel/random/boot_id", "r");
    if (!input) return KModErr::ERR_MODULE_READ_FILE;
    const bool valid = fgets(boot, sizeof(boot), input) && strlen(boot) == 37 && boot[36] == '\n';
    fclose(input);
    if (!valid) return KModErr::ERR_MODULE_READ_FILE;
    std::vector<uint8_t> ledger;
    thread_local std::vector<uint8_t>* output = nullptr;
    output = &ledger;
    auto error = read_blob_disk_storage_with_cb(retention_key, [](const void* data, uint64_t size) {
        if (!output) return;
        if (size != 64 || !data) { output->assign(1, 0); return; }
        const auto* bytes = static_cast<const uint8_t*>(data);
        output->assign(bytes, bytes + size);
    });
    output = nullptr;
    if (is_failed(error) && error != KModErr::ERR_MODULE_STORAGE_NOT_FOUND) return error;
    if (!ledger.empty() && (ledger.size() != 64 || ledger[0] != 1))
        return KModErr::ERR_MODULE_STORAGE_TYPE;
    if (ledger.empty() || memcmp(ledger.data() + 4, boot, 36) != 0) {
        ledger.assign(64, 0);
        ledger[0] = 1;
        memcpy(ledger.data() + 4, boot, 36);
    }
    uint64_t used = 0;
    uint32_t generations = 0;
    memcpy(&used, ledger.data() + 40, sizeof(used));
    memcpy(&generations, ledger.data() + 48, sizeof(generations));
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t charged = ((uint64_t(bytes) + page - 1) / page) * page;
    constexpr uint64_t maximum = 16 * 1024 * 1024;
    if (generations >= 4096 || charged > maximum || used > maximum - charged)
        return KModErr::ERR_MODULE_ALLOC_MEM;
    used += charged;
    ++generations;
    memcpy(ledger.data() + 40, &used, sizeof(used));
    memcpy(ledger.data() + 48, &generations, sizeof(generations));
    return maplesu_store_su_retention(ledger.data(), ledger.size());
}

// Query PackageManager only for interactive grants with missing UID mappings.
// Early boot must not depend on Binder readiness.
bool resolve_from_package_manager(std::unordered_map<std::string, uint32_t>& uids) {
    FILE* pipe = popen("/system/bin/timeout 5 /system/bin/cmd package list packages -U --user 0 2>/dev/null", "r");
    if (!pipe) return false;
    std::unique_ptr<FILE, decltype(&pclose)> owner(pipe, pclose);
    std::unordered_map<std::string, uint32_t> resolved;
    char line[1024];
    while (fgets(line, sizeof(line), pipe)) {
        char package[256], number[32], extra;
        if (sscanf(line, "package:%255s uid:%31s %c", package, number, &extra) != 2) continue;
        uint32_t uid = 0;
        const auto end = number + strlen(number);
        const auto result = std::from_chars(number, end, uid);
        if (result.ec == std::errc{} && result.ptr == end && uid != 0)
            resolved.emplace(package, uid);
    }
    if (pclose(owner.release()) != 0) return false;
    for (auto& item : uids) {
        const auto found = resolved.find(item.first);
        if (item.second == 0 && found != resolved.end()) item.second = found->second;
    }
    return true;
}
}

KModErr maplesu::su_auth::detail::write_allowlist(
        const std::vector<std::string>& packages, const char* required_package, bool refresh_existing,
        ResultV1& result) {
    using namespace kernel_module;
    const auto prepared = maplesu_prepare_su_exec();
    if (is_failed(prepared)) return fail(result, prepared, Phase::WriterPrepare);
    auto mappings = android_pkgmap::read_pkg_uids_from_packages_list(packages, "/data/system/packages.list");
    if (required_package || refresh_existing) {
        for (const auto& package : packages) mappings.emplace(package, 0);
        if (std::any_of(mappings.begin(), mappings.end(), [](const auto& item) { return item.second == 0; })) {
            // The upstream resolver normally includes unresolved names with UID 0.
            if (required_package) mappings.emplace(required_package, 0);
            // A failed query is not evidence that the unresolved old grants were
            // uninstalled. Keep the entire live table and let restoration retry.
            if (!resolve_from_package_manager(mappings))
                return fail(result, KModErr::ERR_MODULE_SU_LIST_NOT_FOUND, Phase::WriterUidResolve);
        }
        if (required_package) {
            const auto target = mappings.find(required_package);
            if (target == mappings.end() || target->second == 0)
                return fail(result, KModErr::ERR_MODULE_SU_LIST_NOT_FOUND, Phase::WriterUidResolve);
        }
    }
    std::vector<uint32_t> table;
    for (const auto& package : packages) {
        const auto entry = mappings.find(package);
        if (entry != mappings.end() && entry->second != 0) table.push_back(entry->second);
    }
    // Uninstalled, previously granted packages may remain in the stored list.
    // They must not block a new valid grant, nor become a UID-0 authorization.
    std::sort(table.begin(), table.end());
    table.erase(std::unique(table.begin(), table.end()), table.end());
    if (table.size() >= UINT32_MAX / sizeof(uint32_t))
        return fail(result, KModErr::ERR_MODULE_PARAM, Phase::WriterInputRange);
    table.push_back(0);
    std::vector<uint32_t> readback(table.size());
    uint64_t control = 0, old = 0;
    auto error = read_uint64_disk_storage(control_key, control);
    if (is_failed(error)) return fail(result, error, Phase::WriterControlLookup);
    if (control == 0) return fail(result, KModErr::ERR_MODULE_UID_PP_ADDR_NOT_FOUND, Phase::WriterControlLookup);
    error = read_kernel_mem(control, &old, sizeof(old));
    if (is_failed(error)) return fail(result, error, Phase::WriterControlRead);
    // Empty input is an explicit revoke-all. Nonempty input with no resolved
    // UID must not accidentally erase a live table when package data is absent.
    // A new boot control block (old == 0) can still install its empty hook.
    if ((old != 0 || refresh_existing) && !packages.empty() && table.size() == 1)
        return fail(result, KModErr::ERR_MODULE_SU_LIST_NOT_FOUND, Phase::WriterUidResolve);
    if (old != 0 && table.size() > 1) {
        // Read only up to the old terminator: a larger desired table must not
        // cause a bulk read beyond the allocation holding a shorter old table.
        bool same = true;
        for (size_t index = 0; index < table.size(); ++index) {
            uint32_t uid = 0;
            error = read_kernel_mem(old + index * sizeof(uid), &uid, sizeof(uid));
            if (is_failed(error)) return fail(result, error, Phase::WriterTableCompare);
            if (uid != table[index]) { same = false; break; }
            if (uid == 0) break;
        }
        if (same) {
            error = maplesu_sync_su_copies_from_control(
                control);
            if (is_failed(error)) set_delivery(result, Delivery::Unknown);
            return fail(result, error, Phase::WriterCopySync);
        }
    }
    // Discard the legacy retirement record without releasing its snapshot.
    error = write_uint64_disk_storage(retired_key, 0);
    if (is_failed(error)) return fail(result, error, Phase::WriterRetireStore);
    uint64_t replacement = 0;
    if (table.size() > 1) {
        const auto bytes = static_cast<uint32_t>(table.size() * sizeof(uint32_t));
        error = reserve_snapshot(bytes);
        if (is_failed(error)) return fail(result, error, Phase::WriterReserve);
        error = alloc_kernel_mem(bytes, replacement);
        if (is_failed(error)) return fail(result, error, Phase::WriterAllocate);
        if (replacement == 0) return fail(result, KModErr::ERR_MODULE_ALLOC_MEM, Phase::WriterAllocate);
        error = write_kernel_mem(replacement, table.data(), bytes, KernMemProt::KMP_RW);
        if (is_failed(error)) fail(result, error, Phase::WriterTableWrite);
        if (!is_failed(error)) {
            error = read_kernel_mem(replacement, readback.data(), bytes);
            if (!is_failed(error) && table != readback) error = KModErr::ERR_MODULE_PARAM;
            if (is_failed(error)) fail(result, error, Phase::WriterTableVerify);
        }
        if (is_failed(error)) {
            recover(result, Phase::WriterFreeUnpublished, free_kernel_mem(replacement));
            return finish(result, error);
        }
    }
    const std::vector<uint32_t> copy_uids(table.begin(), table.end() - 1);
    error = maplesu_sync_su_copies(copy_uids);
    if (is_failed(error)) {
        fail(result, error, Phase::WriterCopySync);
        set_delivery(result, Delivery::Unknown);
        const auto restored = maplesu_sync_su_copies_from_control(
            control);
        recover(result, Phase::WriterRestoreCopies, restored);
        if (replacement) recover(result, Phase::WriterFreeUnpublished, free_kernel_mem(replacement));
        return finish(result, is_failed(restored) ? restored : error);
    }
    error = write_kernel_rw_mem_atomic64(control, replacement);
    if (is_failed(error)) {
        // A request can fail during executor cleanup after its store already
        // happened. Publication was attempted, so readers may hold this table.
        return fail(result, error, Phase::WriterControlPublish);
    }
    return finish(result, KModErr::OK);
}

extern "C" KModErr maplesu_write_su_allowlist(
        const std::vector<std::string>& packages, const char* required_package, bool refresh_existing) {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Refresh);
    return maplesu::su_auth::detail::write_allowlist(packages, required_package, refresh_existing, result);
}
