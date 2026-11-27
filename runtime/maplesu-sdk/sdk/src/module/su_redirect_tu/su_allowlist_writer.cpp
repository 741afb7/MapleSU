// The SDK's add/init/remove/clear paths share this writer. Keep the live UID
// pointer intact until its replacement has been resolved, written and verified.
#include "module_err_def.h"
#include "module_base_disk_storage.h"
#include "module_base_kernel_mem.h"
#include "su_uid_lifetime_private.h"
#include "module_base_kernel_request.h"
#include "su_uid_copies.h"
#include "su_auth_result_private.h"
#include "su_uid_profile_private.h"
#include "maplesu_request_private.h"
#include <cerrno>
extern "C" KModErr maplesu_prepare_su_exec();
#include <algorithm>
#include <charconv>
#include <cstdio>
#include <cstring>
#include <memory>
#include <map>
#include <limits>
#include <sys/stat.h>
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

constexpr const char* profile_prefix = "@maplesu_uid_v1:";
struct BoundProfile {
    maplesu::su_auth::UidProfileV1 profile{};
    uint64_t device = 0, inode = 0;
    std::string members;
};
bool valid_package(const std::string& package) {
    return !package.empty() && package.size() < sizeof(maplesu::su_auth::UidProfileV1::package) &&
        std::all_of(package.begin(), package.end(), [](unsigned char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '.' || c == '_';
        });
}
bool valid_uid(uint32_t uid) {
    // Android full UIDs are positive signed ints; mirror KSU's system-UID gate.
    return uid <= uint32_t(INT32_MAX) && (uid >= 2000 || uid == 1000);
}
bool installation(uint32_t uid, const char* package, uint64_t& device, uint64_t& inode) {
    const auto user = std::to_string(uid / 100000);
    std::vector<std::string> paths{
        "/data/user_de/" + user + '/' + package,
        "/data/user/" + user + '/' + package,
    };
    if (uid < 100000) paths.emplace_back(std::string("/data/data/") + package);
    // Framework package has a system-owned data root rather than an app data directory.
    if (uid == 1000 && strcmp(package, "android") == 0) paths.emplace_back("/data/system");
    for (const auto& path : paths) {
        struct stat st{};
        if (lstat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode) && st.st_uid == uid) {
            device = static_cast<uint64_t>(st.st_dev);
            inode = static_cast<uint64_t>(st.st_ino);
            return inode != 0;
        }
    }
    return false;
}
bool installation_group(uint32_t uid, const char* package, std::string& members) {
    FILE* input = fopen("/data/system/packages.list", "r");
    if (!input) return false;
    std::map<std::string, std::pair<uint64_t, uint64_t>> installed;
    char line[4096];
    bool valid = true;
    while (fgets(line, sizeof(line), input)) {
        char name[256], number[32];
        if (!strchr(line, '\n') && !feof(input)) { valid = false; break; }
        if (sscanf(line, "%255s %31s", name, number) != 2) continue;
        uint32_t app_id = 0;
        const auto end = number + strlen(number);
        const auto parsed = std::from_chars(number, end, app_id);
        if (parsed.ec != std::errc{} || parsed.ptr != end || app_id >= 100000 || app_id != uid % 100000) continue;
        if (!valid_package(name)) { valid = false; break; }
        uint64_t device = 0, inode = 0;
        if (installation(uid, name, device, inode)) installed.emplace(name, std::make_pair(device, inode));
    }
    valid = valid && !ferror(input);
    fclose(input);
    if (!valid || !installed.count(package)) return false;
    members.clear();
    for (const auto& row : installed) {
        if (!members.empty()) members += ';';
        members += row.first + '=' + std::to_string(row.second.first) + ',' + std::to_string(row.second.second);
    }
    return true;
}
bool valid_members(const std::string& members) {
    if (members.empty()) return false;
    std::string previous;
    size_t start = 0;
    while (start < members.size()) {
        const auto end = members.find(';', start);
        const auto stop = end == std::string::npos ? members.size() : end;
        const auto equal = members.find('=', start), comma = members.find(',', start);
        if (equal == std::string::npos || comma == std::string::npos || equal >= comma || comma >= stop) return false;
        const auto name = members.substr(start, equal - start);
        if (!valid_package(name) || (!previous.empty() && name <= previous)) return false;
        uint64_t device = 0, inode = 0;
        const auto d = std::from_chars(members.data() + equal + 1, members.data() + comma, device);
        const auto i = std::from_chars(members.data() + comma + 1, members.data() + stop, inode);
        if (d.ec != std::errc{} || d.ptr != members.data() + comma || i.ec != std::errc{} ||
            i.ptr != members.data() + stop || inode == 0) return false;
        previous = name;
        if (end == std::string::npos) return true;
        start = end + 1;
    }
    return false;
}
KModErr parse_records(const std::vector<std::string>& raw, std::vector<std::string>& legacy,
                     std::map<uint32_t, BoundProfile>& profiles) {
    for (const auto& entry : raw) {
        if (entry.compare(0, strlen(profile_prefix), profile_prefix) != 0) {
            if (!valid_package(entry)) return KModErr::ERR_MODULE_PARAM;
            legacy.push_back(entry);
            continue;
        }
        const auto start = strlen(profile_prefix);
        const auto separator = entry.find(':', start);
        if (separator == std::string::npos || separator + 3 >= entry.size() ||
            entry[separator + 2] != ':' || (entry[separator + 1] != '0' && entry[separator + 1] != '1'))
            return KModErr::ERR_MODULE_PARAM;
        uint32_t uid = 0;
        const auto number = std::from_chars(entry.data() + start, entry.data() + separator, uid);
        const auto device_end = entry.find(':', separator + 3);
        const auto inode_end = device_end == std::string::npos ? std::string::npos : entry.find(':', device_end + 1);
        if (device_end == std::string::npos || inode_end == std::string::npos) return KModErr::ERR_MODULE_PARAM;
        BoundProfile bound{};
        const auto device_number = std::from_chars(entry.data() + separator + 3, entry.data() + device_end, bound.device);
        const auto inode_number = std::from_chars(entry.data() + device_end + 1, entry.data() + inode_end, bound.inode);
        const auto package_end = entry.find(':', inode_end + 1);
        if (package_end == std::string::npos) return KModErr::ERR_MODULE_PARAM;
        const auto package = entry.substr(inode_end + 1, package_end - inode_end - 1);
        bound.members = entry.substr(package_end + 1);
        if (number.ec != std::errc{} || number.ptr != entry.data() + separator ||
            device_number.ec != std::errc{} || device_number.ptr != entry.data() + device_end ||
            inode_number.ec != std::errc{} || inode_number.ptr != entry.data() + inode_end ||
            !valid_uid(uid) || !valid_package(package) || profiles.count(uid))
            return KModErr::ERR_MODULE_PARAM;
        auto& profile = bound.profile;
        profile.uid = uid;
        profile.allow = entry[separator + 1] == '1';
        if (profile.allow && (bound.inode == 0 || !valid_members(bound.members))) return KModErr::ERR_MODULE_PARAM;
        if (!profile.allow && (bound.device || bound.inode || !bound.members.empty())) return KModErr::ERR_MODULE_PARAM;
        memcpy(profile.package, package.c_str(), package.size() + 1);
        profiles.emplace(uid, bound);
    }
    return KModErr::OK;
}
KModErr legacy_mappings(const std::vector<std::string>& legacy,
                       std::unordered_map<std::string, uint32_t>& mappings, bool interactive) {
    if (legacy.empty()) return KModErr::OK;
    // An unreadable package map is unknown, never an empty successful grant set.
    FILE* input = fopen("/data/system/packages.list", "r");
    const bool readable = input != nullptr;
    if (input) fclose(input);
    mappings = android_pkgmap::read_pkg_uids_from_packages_list(legacy, "/data/system/packages.list");
    const bool missing = std::any_of(mappings.begin(), mappings.end(), [](const auto& row) { return row.second == 0; });
    if ((!readable || missing) && interactive && !resolve_from_package_manager(mappings))
        return KModErr::ERR_MODULE_SU_LIST_NOT_FOUND;
    if (!readable && !interactive) return KModErr::ERR_MODULE_SU_LIST_NOT_FOUND;
    for (const auto& row : mappings)
        if (row.second && (row.second >= 100000 || !valid_uid(row.second))) return KModErr::ERR_MODULE_PARAM;
    return KModErr::OK;
}
}

KModErr maplesu::su_auth::detail::collect_uid_profiles(
        const std::vector<std::string>& raw, std::vector<UidProfileV1>& output) {
    std::vector<std::string> legacy;
    std::map<uint32_t, BoundProfile> explicit_profiles;
    auto error = parse_records(raw, legacy, explicit_profiles);
    if (is_failed(error)) return error;
    std::unordered_map<std::string, uint32_t> mappings;
    error = legacy_mappings(legacy, mappings, false);
    if (is_failed(error)) return error;
    std::map<uint32_t, UidProfileV1> profiles;
    for (const auto& package : legacy) {
        const auto found = mappings.find(package);
        if (found == mappings.end() || found->second == 0) continue;
        UidProfileV1 profile{};
        profile.uid = found->second;
        profile.allow = 1;
        memcpy(profile.package, package.c_str(), package.size() + 1);
        profiles.emplace(profile.uid, profile);
    }
    // A shared Linux UID has one decision, regardless of which member's row changed.
    for (const auto& row : explicit_profiles) {
        uint64_t device = 0, inode = 0;
        const auto& bound = row.second;
        std::string members;
        if (!bound.profile.allow || (installation(row.first, bound.profile.package, device, inode) &&
                device == bound.device && inode == bound.inode &&
                installation_group(row.first, bound.profile.package, members) && members == bound.members))
            profiles[row.first] = bound.profile;
        else profiles.erase(row.first); // A stale grant also masks its old user-0 compatibility row.
    }
    output.clear();
    for (const auto& row : profiles) output.push_back(row.second);
    return KModErr::OK;
}

KModErr maplesu::su_auth::detail::replace_uid_profile(
        std::vector<std::string>& raw, uint32_t uid, const char* package, bool allow) {
    if (!package || strnlen(package, 256) == 256 || !valid_uid(uid) || !valid_package(package))
        return KModErr::ERR_MODULE_PARAM;
    std::vector<std::string> legacy;
    std::map<uint32_t, BoundProfile> profiles;
    auto error = parse_records(raw, legacy, profiles);
    if (is_failed(error)) return error;
    std::unordered_map<std::string, uint32_t> mappings;
    if (uid < 100000) {
        error = legacy_mappings(legacy, mappings, true);
        if (is_failed(error)) return error;
        legacy.erase(std::remove_if(legacy.begin(), legacy.end(), [&](const auto& name) {
            return name == package || mappings[name] == uid;
        }), legacy.end());
    }
    BoundProfile bound{};
    auto& profile = bound.profile;
    profile.uid = uid;
    profile.allow = allow;
    memcpy(profile.package, package, strlen(package) + 1);
    if (allow && (!installation(uid, package, bound.device, bound.inode) ||
            !installation_group(uid, package, bound.members)))
        return KModErr::ERR_MODULE_SU_LIST_NOT_FOUND;
    profiles[uid] = bound;
    raw = std::move(legacy);
    for (const auto& row : profiles) raw.emplace_back(std::string(profile_prefix) +
        std::to_string(row.first) + ':' + (row.second.profile.allow ? "1:" : "0:") +
        std::to_string(row.second.device) + ':' + std::to_string(row.second.inode) + ':' + row.second.profile.package + ':' + row.second.members);
    return KModErr::OK;
}

KModErr maplesu::su_auth::detail::replace_legacy_uid_profile(
        std::vector<std::string>& raw, const char* package, bool allow) {
    if (!package || strnlen(package, 256) == 256 || !valid_package(package)) return KModErr::ERR_MODULE_PARAM;
    std::unordered_map<std::string, uint32_t> mappings;
    const auto error = legacy_mappings({package}, mappings, true);
    if (is_failed(error)) return error;
    const auto found = mappings.find(package);
    if (found == mappings.end() || found->second == 0) {
        if (!allow && std::find(raw.begin(), raw.end(), package) != raw.end()) {
            raw.erase(std::remove(raw.begin(), raw.end(), package), raw.end());
            return KModErr::OK;
        }
        return KModErr::ERR_MODULE_SU_LIST_NOT_FOUND;
    }
    if (!allow) {
        std::vector<UidProfileV1> before;
        const auto loaded = collect_uid_profiles(raw, before);
        if (is_failed(loaded)) return loaded;
        if (std::none_of(before.begin(), before.end(), [&](const auto& row) { return row.uid == found->second && row.allow; }))
            return KModErr::ERR_MODULE_SU_LIST_NOT_FOUND;
    }
    return replace_uid_profile(raw, found->second, package, allow);
}

KModErr maplesu::su_auth::detail::write_allowlist(
        const std::vector<std::string>& packages, const char* required_package, bool refresh_existing,
        ResultV1& result) {
    using namespace kernel_module;
    // The inode-bound v1 file and bare UID control are legacy compatibility,
    // not a trusted installation registry for the typed Owner domain.
    if (maplesu_request_owner_mode()) {
        errno = EOPNOTSUPP;
        return fail(result, KModErr::ERR_MODULE_TEST_CHANNEL, Phase::WriterUidResolve);
    }
    const auto prepared = maplesu_prepare_su_exec();
    if (is_failed(prepared)) return fail(result, prepared, Phase::WriterPrepare);
    (void)refresh_existing;
    std::vector<UidProfileV1> profiles;
    const auto resolved = collect_uid_profiles(packages, profiles);
    if (is_failed(resolved)) return fail(result, resolved, Phase::WriterUidResolve);
    std::vector<uint32_t> table;
    for (const auto& profile : profiles) if (profile.allow) table.push_back(profile.uid);
    if (required_package && std::none_of(profiles.begin(), profiles.end(), [&](const auto& row) {
            return row.allow && row.uid < 100000 && strcmp(row.package, required_package) == 0;
        })) return fail(result, KModErr::ERR_MODULE_SU_LIST_NOT_FOUND, Phase::WriterUidResolve);
    // Uninstalled, previously granted packages may remain in the stored list.
    // They must not block a new valid grant, nor become a UID-0 authorization.
    std::sort(table.begin(), table.end());
    table.erase(std::unique(table.begin(), table.end()), table.end());
    if (table.size() > 4095)
        return fail(result, KModErr::ERR_MODULE_PARAM, Phase::WriterInputRange);
    table.push_back(0);
    std::vector<uint32_t> readback(table.size());
    uint64_t control = 0, old = 0;
    auto error = read_uint64_disk_storage(control_key, control);
    if (is_failed(error)) return fail(result, error, Phase::WriterControlLookup);
    if (control == 0) return fail(result, KModErr::ERR_MODULE_UID_PP_ADDR_NOT_FOUND, Phase::WriterControlLookup);
    error = read_kernel_mem(control, &old, sizeof(old));
    if (is_failed(error)) return fail(result, error, Phase::WriterControlRead);
    uint64_t private_index = 0;
    error = maplesu_su_uid_index_state(control, private_index);
    if (is_failed(error)) return fail(result, error, Phase::WriterIndexPrepare);
    // A file containing only explicit denials intentionally publishes an empty table.
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
            error = maplesu_su_uid_index_prepare(old, static_cast<uint32_t>(table.size() - 1));
            if (is_failed(error)) return fail(result, error, Phase::WriterIndexPrepare);
            error = maplesu_sync_su_copies_from_control(
                control);
            if (is_failed(error)) set_delivery(result, Delivery::Unknown);
            return fail(result, error, Phase::WriterCopySync);
        }
    }
    // A controller published by older code has no reader protection. Never
    // reclaim its table based on a version guess or an untrusted address.
    if (old) {
        error = maplesu_su_uid_reader_require(control);
        if (is_failed(error)) return fail(result, error, Phase::WriterRetireStore);
    }
    uint64_t replacement = 0;
    if (table.size() > 1) {
        const auto bytes = static_cast<uint32_t>(table.size() * sizeof(uint32_t));
        // Cross-process state must use the existing scoped raw Legacy allocator,
        // not an ordinary session staging object that dies with this child.
        error = request_kernel_alloc_mem(bytes, replacement);
        if (is_failed(error)) return fail(result, error, Phase::WriterAllocate);
        if (replacement == 0) return fail(result, KModErr::ERR_MODULE_ALLOC_MEM, Phase::WriterAllocate);
        error = write_kernel_mem(replacement, table.data(), bytes, KernMemProt::KMP_RW);
        if (is_failed(error)) fail(result, error, Phase::WriterTableWrite);
        if (!is_failed(error)) {
            error = read_kernel_mem(replacement, readback.data(), bytes);
            if (!is_failed(error) && table != readback) error = KModErr::ERR_MODULE_PARAM;
            if (is_failed(error)) fail(result, error, Phase::WriterTableVerify);
        }
        if (!is_failed(error)) {
            error = maplesu_su_uid_index_prepare(replacement, static_cast<uint32_t>(table.size() - 1));
            if (is_failed(error)) fail(result, error, Phase::WriterIndexPrepare);
        }
        if (is_failed(error)) {
            recover(result, Phase::WriterFreeUnpublished, maplesu_su_uid_index_release(replacement));
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
        if (replacement) recover(result, Phase::WriterFreeUnpublished, maplesu_su_uid_index_release(replacement));
        return finish(result, is_failed(restored) ? restored : error);
    }
    error = write_kernel_rw_mem_atomic64(control, replacement);
    if (is_failed(error)) {
        // Executor cleanup may fail after the store. Observe the actual control:
        // the transaction retires a still-published replacement after rollback.
        fail(result, error, Phase::WriterControlPublish);
        uint64_t published = 0;
        const auto observed = read_kernel_mem(control, &published, sizeof(published));
        recover(result, Phase::TransactionObserveControl, observed);
        if (replacement && is_ok(observed) && published != replacement) {
            // During first initialization no hooks exist yet; otherwise the
            // installed reader protocol must drain any potentially exposed scan.
            auto drained = old ? maplesu_su_uid_reader_require(control) : KModErr::OK;
            if (old && is_ok(drained)) drained = maplesu_su_uid_synchronize();
            recover(result, Phase::WriterFreeUnpublished, drained);
            if (is_ok(drained))
                recover(result, Phase::WriterFreeUnpublished, maplesu_su_uid_index_release(replacement));
        }
        return finish(result, error);
    }
    return finish(result, KModErr::OK);
}

extern "C" KModErr maplesu_write_su_allowlist(
        const std::vector<std::string>& packages, const char* required_package, bool refresh_existing) {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Refresh);
    return maplesu::su_auth::detail::write_allowlist(packages, required_package, refresh_existing, result);
}
