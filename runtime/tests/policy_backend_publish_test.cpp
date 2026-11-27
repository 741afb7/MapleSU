#include "backend_publish.h"
#include "../maplesu-sdk/sdk/include/maplesu_request_private.h"
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"
#include <cassert>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <vector>

static std::string scenario;
static uint64_t auth_state = 1;
static pid_t parent_pid = 0;
extern "C" void __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(const char*);
static bool legacy() { return scenario.rfind("legacy_", 0) == 0; }
struct Calls { unsigned selected, rooted, closed, admitted, forks; };
static Calls* calls;
extern "C" pid_t __real_fork();
extern "C" pid_t __wrap_fork() {
    assert(getpid() == parent_pid);
    ++calls->forks;
    return __real_fork();
}
namespace kernel_module {
uint64_t maplesu_request_auth_state() { return auth_state; }
KModErr maplesu_request_owner_channel(maplesu_metadata_owner_v3&, uint64_t&, RequestOwnerBinding&) {
    assert(getpid() == parent_pid && maplesu_request_manager_mode());
    ++calls->admitted;
    if (scenario == "admission_denied") return KModErr::ERR_MODULE_ROOT_KEY;
    return scenario == "admission_transport_failure" ? KModErr::ERR_MODULE_TEST_CHANNEL : KModErr::OK;
}
KModErr maplesu_request_helper_vendor(uint64_t, unsigned, maplesu_installation_state_v3&) {
    abort();  // The real policy entry rejects helper identity before admission.
}
KModErr maplesu_request_set_auth_mode(RequestAuthMode mode) {
    assert(getpid() != parent_pid);
    assert(mode == RequestAuthMode::ManagerOwner);
    ++calls->selected;
    if (scenario == "owner_select_failure") return KModErr::ERR_MODULE_TEST_CHANNEL;
    auth_state = ((auth_state & ~UINT64_C(3)) + 4) | static_cast<uint64_t>(mode);
    return KModErr::OK;
}
KModErr maplesu_request_owner_close() {
    assert(getpid() != parent_pid && maplesu_request_manager_mode());
    ++calls->closed;
    return scenario == "owner_close_failure" || scenario == "owner_first_failure" ||
        scenario == "write_close_failure" || scenario == "label_close_failure" || scenario == "readback_close_failure"
        ? KModErr::ERR_MODULE_TEST_CHANNEL : KModErr::OK;
}
CleanupReceiptScope::CleanupReceiptScope() noexcept
    : saved_errno_(errno), saved_process_(getpid()), saved_reply_{} {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept {}
}

namespace maplesu::sdk {
KModErr get_root() {
    assert(getpid() != parent_pid);
    assert(legacy() ? kernel_module::maplesu_request_auth_legacy(auth_state) : kernel_module::maplesu_request_manager_mode());
    ++calls->rooted;
    return scenario == "root_failure" || scenario == "legacy_root_failure" || scenario == "owner_root_failure" || scenario == "owner_first_failure"
        ? KModErr::ERR_MODULE_ROOT_KEY : KModErr::OK;
}
}
extern "C" ssize_t __real_write(int, const void*, size_t);
extern "C" ssize_t __wrap_write(int fd, const void* data, size_t size) {
    if ((scenario == "write_failure" || scenario == "legacy_write_failure" || scenario == "write_close_failure") && size > 8) { errno = ENOSPC; return -1; }
    return __real_write(fd, data, size);
}
extern "C" int __real_fsetxattr(int, const char*, const void*, size_t, int);
extern "C" ssize_t __real_fgetxattr(int, const char*, void*, size_t);
extern "C" ssize_t __wrap_fgetxattr(int fd, const char* name, void* data, size_t size) {
    assert(strcmp(name, "security.selinux") == 0);
    if (scenario == "readback_failure" || scenario == "readback_close_failure") { errno = EIO; return -1; }
    return __real_fgetxattr(fd, "user.maplesu.selinux", data, size);
}
extern "C" int __wrap_fsetxattr(int fd, const char* name, const void* data, size_t size, int flags) {
    assert(strcmp(name, "security.selinux") == 0);
    if (scenario == "idempotent" || scenario == "label_failure" || scenario == "label_close_failure") { errno = EACCES; return -1; }
    return __real_fsetxattr(fd, "user.maplesu.selinux", data, size, flags);
}
extern "C" int __real_close(int);
extern "C" int __wrap_close(int fd) {
    struct stat st{};
    const bool fail = scenario == "file_close_failure" && fstat(fd, &st) == 0 && S_ISREG(st.st_mode);
    const int result = __real_close(fd);
    if (fail) { errno = EIO; return -1; }
    return result;
}
extern "C" uid_t __real_getuid();
extern "C" uid_t __real_geteuid();
extern "C" uid_t __wrap_getuid() {
    return getpid() != parent_pid && scenario == "root_identity_failure" ? 10315 : __real_getuid();
}
extern "C" uid_t __wrap_geteuid() {
    return getpid() != parent_pid && scenario == "root_identity_failure" ? 10315 : __real_geteuid();
}
static std::string contents(const std::filesystem::path& path) {
    std::ifstream stream(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}
int main(int argc, char** argv) {
    assert(argc == 2);
    scenario = argv[1];
    parent_pid = getpid();
    calls = static_cast<Calls*>(mmap(nullptr, sizeof(Calls), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0));
    assert(calls != MAP_FAILED);
    *calls = {};
    auth_state = scenario == "manager_generation" ? 9 : 1;
    if (scenario == "invalid_auth_zero") auth_state = 0;
    if (scenario == "invalid_auth_tag") auth_state = 2;
    if (scenario == "invalid_auth_exhausted") auth_state = UINT64_MAX;
    if (scenario == "helper_identity") auth_state = 3;
    if (legacy()) {
        auth_state = 4;
        __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(scenario == "legacy_invalid_credential" ? "" : "0123456789abcdeffixture");
    }
    const uint64_t parent_auth = auth_state;
    const std::filesystem::path root("/tmp/maplesu-policy-publish-entry");
    std::filesystem::remove_all(root);
    std::filesystem::create_directories(root / "adb");
    const std::string digest(64, 'a');
    const auto directory = root / "adb/maplesu/bin";
    const auto target = directory / ("libmaplesu_policy_boot." + digest + ".so");
    const auto old = directory / ("libmaplesu_policy_boot." + std::string(64, 'b') + ".so");
    std::vector<unsigned char> bytes(1024 * 1024, 'N');
    memcpy(bytes.data(), "\177ELF\2\1", 6);
    bytes[16] = 3; bytes[17] = 0; bytes[18] = 183; bytes[19] = 0;
    std::filesystem::create_directories(directory);
    std::ofstream(old) << "old-backend";
    if (scenario == "write_failure" || scenario == "write_close_failure" || scenario == "symlink_file" || scenario == "hardlink_file") {
        if (scenario == "symlink_file") assert(symlink(old.c_str(), target.c_str()) == 0);
        else if (scenario == "hardlink_file") assert(link(old.c_str(), target.c_str()) == 0);
        else std::ofstream(target) << "previous";
    }
    if (scenario == "unsafe_bin") assert(chmod(directory.c_str(), 0777) == 0);
    if (scenario == "symlink_parent") {
        std::filesystem::rename(directory, root / "bin-other");
        assert(symlink((root / "bin-other").c_str(), directory.c_str()) == 0);
    }
    int64_t result;
    if (scenario == "invalid_digest") result = maplesu::module_policy::publish_backend("../invalid", bytes.data(), bytes.size());
    else if (scenario == "invalid_elf") {
        bytes[18] = 62;
        result = maplesu::module_policy::publish_backend(digest.c_str(), bytes.data(), bytes.size());
    } else if (scenario == "oversize") result = maplesu::module_policy::publish_backend(digest.c_str(), bytes.data(), 33 * 1024 * 1024);
    else {
        if (scenario == "idempotent" || scenario == "repair_label") {
            const auto saved_scenario = scenario;
            scenario = "fresh";
            assert(maplesu::module_policy::publish_backend(digest.c_str(), bytes.data(), bytes.size()) == 0);
            scenario = saved_scenario;
            if (scenario == "repair_label") assert(removexattr(target.c_str(), "user.maplesu.selinux") == 0);
        }
        struct stat before{};
        if (scenario == "idempotent") assert(stat(target.c_str(), &before) == 0);
        result = maplesu::module_policy::publish_backend(digest.c_str(), bytes.data(), bytes.size());
        if (scenario == "idempotent") {
            struct stat after{};
            assert(stat(target.c_str(), &after) == 0 && before.st_ino == after.st_ino);
        }
    }
    assert(auth_state == parent_auth);
    const bool rejected_before_admission = scenario.rfind("invalid_", 0) == 0 || scenario == "helper_identity" || scenario == "oversize";
    const bool rejected_by_admission = scenario == "admission_denied" || scenario == "admission_transport_failure" || scenario == "legacy_invalid_credential";
    const bool rejected_before_fork = rejected_before_admission || rejected_by_admission;
    const unsigned attempts = scenario == "idempotent" || scenario == "repair_label" ? 2 : 1;
    assert(calls->selected == (rejected_before_fork || legacy() ? 0 : attempts));
    assert(calls->rooted == (rejected_before_fork || scenario == "owner_select_failure" ? 0 : attempts));
    assert(calls->closed == (rejected_before_fork || legacy() ? 0 : attempts));
    assert(calls->admitted == (rejected_before_admission || legacy() ? 0 : attempts));
    assert(calls->forks == (rejected_before_fork ? 0 : attempts));
    if (rejected_before_admission) assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_PARAM));
    if (scenario == "admission_denied" || scenario == "legacy_invalid_credential") assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY));
    if (scenario == "admission_transport_failure") assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_TEST_CHANNEL));
    if (scenario == "owner_first_failure" || scenario == "owner_root_failure")
        assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY));
    if (scenario == "owner_select_failure" || scenario == "owner_close_failure")
        assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_TEST_CHANNEL));
    if (scenario == "root_failure" || scenario == "root_identity_failure" || scenario == "legacy_root_failure")
        assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY));
    if (scenario == "write_failure" || scenario == "legacy_write_failure" || scenario == "write_close_failure" || scenario == "readback_failure" ||
        scenario == "readback_close_failure" || scenario == "file_close_failure")
        assert(result == static_cast<int64_t>(KModErr::ERR_MODULE_WRITE_FILE));
    if (scenario == "label_failure" || scenario == "label_close_failure")
        assert(result == static_cast<int64_t>(KModErr::ERR_SET_FILE_SELINUX));
    if (scenario == "legacy_fresh" || scenario == "fresh" || scenario == "idempotent" || scenario == "repair_label" ||
        scenario == "owner_fresh" || scenario == "manager_generation" || scenario == "owner_close_failure" ||
        scenario == "readback_failure" || scenario == "readback_close_failure") {
        const bool failed_after_publish = scenario == "owner_close_failure" || scenario == "readback_failure" || scenario == "readback_close_failure";
        assert((failed_after_publish ? result != 0 : result == 0) &&
            contents(target) == std::string(reinterpret_cast<char*>(bytes.data()), bytes.size()));
        struct stat st{};
        assert(stat(target.c_str(), &st) == 0 && st.st_uid == 0 && st.st_nlink == 1 && (st.st_mode & 07777) == 0700);
        char label[64]{};
        constexpr char expected[] = "u:object_r:system_file:s0";
        assert(getxattr(target.c_str(), "user.maplesu.selinux", label, sizeof(label)) == sizeof(expected));
        assert(memcmp(label, expected, sizeof(expected)) == 0);
    } else {
        assert(result != 0);
        if (scenario == "write_failure" || scenario == "write_close_failure") assert(contents(target) == "previous");
        else if (scenario != "symlink_file" && scenario != "hardlink_file") assert(!std::filesystem::exists(target));
    }
    if (scenario == "symlink_parent") assert(contents(root / "bin-other" / old.filename()) == "old-backend");
    else assert(contents(old) == "old-backend");
    for (const auto& file : std::filesystem::recursive_directory_iterator(root))
        assert(file.path().filename().string().find(".maplesu-exec-") != 0);
    std::filesystem::remove_all(root);
    assert(munmap(calls, sizeof(Calls)) == 0);
    printf("PASS forked boot-backend publication %s\n", scenario.c_str());
}
