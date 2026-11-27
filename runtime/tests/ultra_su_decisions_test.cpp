// Actual Legacy getter and durable collector; root/storage/kernel are boundaries.
#include "module_err_def.h"
#include "su_uid_profile_private.h"
#include "su_uid_copies.h"
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <map>
#include <string>
#include <sys/stat.h>
#include <unordered_map>
#include <unistd.h>
#include <vector>

static std::string scenario;
static std::vector<std::string> records;
static std::vector<maplesu::su_auth::UidProfileV1> observed;
static unsigned kernel_reads, delivery_reads;
static constexpr KModErr denied = static_cast<KModErr>(-23456);
namespace maplesu::sdk { KModErr get_root() { return scenario == "root_error" ? denied : KModErr::OK; } }
namespace default_modules::module_su_redirect {
constexpr const char* kUidControlStorageKey = "fixture";
struct SuAllowList {
    bool load() { return scenario != "load_error"; }
    std::vector<std::string> get_apps() { return records; }
} g_suAllowList;
}
namespace kernel_module {
bool maplesu_request_owner_mode() { return false; }
KModErr read_uint64_disk_storage(const char*, uint64_t& out) {
    ++kernel_reads; out = scenario == "absent_control" ? 0 : 0x1000; return KModErr::OK;
}
KModErr read_kernel_mem(uint64_t address, void* out, uint32_t size) {
    ++kernel_reads;
    const uint64_t table = 0x2000;
    const uint32_t values[]{10123, 110124, 0};
    if (address == 0x1000) { assert(size == 8); memcpy(out, &table, 8); }
    else { assert(size == 4 && address >= table && address < table + sizeof(values)); memcpy(out, reinterpret_cast<const char*>(values) + address - table, 4); }
    return KModErr::OK;
}
}
extern "C" KModErr maplesu_verify_su_copies_from_control(uint64_t) {
    ++delivery_reads;
    // Replay the independently observed canonical 0711 versus verifier 0700.
    return scenario == "canonical_0711" ? ERR_SU_DELIVERY_INCOMPLETE : KModErr::OK;
}
namespace android_pkgmap {
std::unordered_map<std::string, uint32_t> read_pkg_uids_from_packages_list(
    const std::vector<std::string>& packages, const std::string&) {
    std::unordered_map<std::string, uint32_t> result;
    for (const auto& package : packages) result[package] = 10123;
    return result;
}
}
namespace maplesu::su_auth::detail {
KModErr owner_runtime_profiles(void (*)(const UidProfileV1*)) { assert(false); return denied; }
}
#include "su-collector-production.inc"
#include "su-getter-production.inc"

static void install(uint32_t uid, const char* package) {
    const auto path = std::string("/data/user_de/") + std::to_string(uid / 100000) + '/' + package;
    std::filesystem::create_directories(path);
    assert(chown(path.c_str(), uid, uid) == 0);
}
static std::string bound(uint32_t uid, const char* package) {
    uint64_t device = 0, inode = 0; std::string members;
    assert(installation(uid, package, device, inode) && installation_group(uid, package, members));
    if (scenario == "stale_installation") ++inode;
    return "@maplesu_uid_v1:" + std::to_string(uid) + ":1:" + std::to_string(device) + ':' +
        std::to_string(inode) + ':' + package + ':' + members;
}
int main(int argc, char** argv) {
    assert(argc == 2); scenario = argv[1];
    std::filesystem::create_directories("/data/system");
    FILE* packages = fopen("/data/system/packages.list", "w"); assert(packages);
    fputs("test.shared_a 10123\ntest.shared_b 10123\ntest.secondary 10124\n", packages); assert(fclose(packages) == 0);
    install(10123, "test.shared_a"); install(10123, "test.shared_b"); install(110124, "test.secondary");
    records = {"test.shared_a", "test.shared_b", bound(10123, "test.shared_a"),
        "@maplesu_uid_v1:10125:0:0:0:test.denied:", bound(110124, "test.secondary")};
    if (scenario == "invalid_record") records.push_back("@maplesu_uid_v1:broken");
    if (scenario == "package_map_missing") assert(unlink("/data/system/packages.list") == 0);
    const auto result = maplesu::su_auth::detail::sdk_get_uid_profiles([](const auto* row) { observed.push_back(*row); });
    if (scenario == "root_error" || scenario == "load_error" || scenario == "invalid_record" || scenario == "package_map_missing") {
        const auto expected = scenario == "root_error" ? denied : scenario == "load_error" ? KModErr::ERR_MODULE_OPEN_FILE :
            scenario == "invalid_record" ? KModErr::ERR_MODULE_PARAM : KModErr::ERR_MODULE_SU_LIST_NOT_FOUND;
        assert(result == expected && observed.empty());
    } else {
        assert(result == KModErr::OK);
        if (scenario == "stale_installation") {
            assert(observed.size() == 1 && observed[0].uid == 10125 && !observed[0].allow);
        } else {
            assert(observed.size() == 3);
            assert(observed[0].uid == 10123 && observed[0].allow && !strcmp(observed[0].package, "test.shared_a"));
            assert(observed[1].uid == 10125 && !observed[1].allow);
            assert(observed[2].uid == 110124 && observed[2].allow);
        }
    }
    // Listing durable decisions must neither inspect nor repair delivery state.
    assert(kernel_reads == 0 && delivery_reads == 0);
    printf("PASS %s durable/fullUID/sharedUID/denial/installation read\n", argv[1]);
}
