#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"
#include "maplesu_sdk/maplesu_su_auth.h"
#include "maplesu_request_private.h"
#include "maplesu_storage_private.h"
#include "su_auth_result_private.h"
#include "su_uid_profile_private.h"
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <map>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace default_modules::module_su_redirect {
KModErr add_su_allowlist(const char*);
KModErr remove_su_allowlist(const char*);
KModErr clear_su_allowlist();
}
extern "C" KModErr maplesu_prepare_su_exec();
static constexpr const char* directory = "/data/ABCDEFGHIJKLMNOPA";
static constexpr const char* private_directory = "/data/ABCDEFGHIJKLMNOPA/u-00000000";
static constexpr const char* legacy_helper = "/data/ABCDEFGHIJKLMNOPA/su";
static constexpr const char* helper = "/data/ABCDEFGHIJKLMNOPA/u-00000000/su";
static constexpr const char* context = "u:object_r:system_file:s0";
static constexpr uint32_t full_uid = 110489;
static constexpr KModErr denied = static_cast<KModErr>(-23456);
static std::map<ino_t, std::string> labels;
static std::string scenario;
static unsigned relabels = 0, root_calls = 0, storage_calls = 0, typed_calls = 0;
static bool backend_allowed = true;

static unsigned descriptor_count() {
    DIR* entries = opendir("/proc/self/fd"); assert(entries);
    unsigned count = 0;
    while (auto* entry = readdir(entries)) if (entry->d_name[0] != '.') ++count;
    assert(closedir(entries) == 0);
    return count;
}

// Only SELinux's xattr decisions are replayed; inode validation, publication,
// modes, descriptors and transaction dispatch are the current production code.
extern "C" ssize_t __wrap_fgetxattr(int fd, const char* name, void* out, size_t size) {
    assert(strcmp(name, "security.selinux") == 0);
    struct stat st{}; assert(fstat(fd, &st) == 0);
    const auto& label = labels[st.st_ino];
    if (label.empty()) { errno = ENODATA; return -1; }
    if (size < label.size() + 1) { errno = ERANGE; return -1; }
    memcpy(out, label.c_str(), label.size() + 1);
    return label.size() + 1;
}
extern "C" int __wrap_fsetxattr(int fd, const char* name, const void* data, size_t size, int flags) {
    assert(strcmp(name, "security.selinux") == 0 && flags == 0);
    assert(size == strlen(context) + 1 && strcmp(static_cast<const char*>(data), context) == 0);
    ++relabels;
    if (scenario == "relabel_denied") { errno = EACCES; return -1; }
    struct stat st{}; assert(fstat(fd, &st) == 0);
    if (scenario != "bad_readback") labels[st.st_ino] = context;
    return 0;
}
namespace maplesu::storage {
KModErr current_system_directory(std::string& out) {
    ++storage_calls;
    if (scenario == "storage_denied") { errno = EACCES; return denied; }
    out = std::string(directory) + '/';
    return KModErr::OK;
}
}
namespace maplesu::sdk {
KModErr get_root() {
    ++root_calls;
    assert(scenario == "root_denied");
    errno = EACCES;
    return denied;
}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state() {
    return scenario == "root_denied" ? 4 : scenario == "unusable_identity" ? 0 : 5;
}
KModErr read_uint64_disk_storage(const char*, uint64_t&) { assert(false); return denied; }
KModErr read_kernel_mem(uint64_t, void*, uint32_t) { assert(false); return denied; }
KModErr write_kernel_rw_mem_atomic64(uint64_t, uint64_t) { assert(false); return denied; }
}
// These raw-kernel boundaries must never run in a usable Owner fixture.
extern "C" KModErr maplesu_freeze_su_copies() { assert(false); return denied; }
extern "C" void maplesu_thaw_su_copies() { assert(false); }
extern "C" KModErr maplesu_open_su_copies_from_control(uint64_t) { assert(false); return denied; }
extern "C" KModErr maplesu_sync_su_copies_from_control(uint64_t) { assert(false); return denied; }
KModErr maplesu_su_uid_reader_require(uint64_t) { assert(false); return denied; }
KModErr maplesu_su_uid_synchronize() { assert(false); return denied; }
KModErr maplesu_su_uid_index_release(uint64_t) { assert(false); return denied; }
extern "C" KModErr maplesu_sdk_get_su_allowlist(void (*)(const maplesu::sdk::su_auth_item*)) {
    assert(false); return denied;
}
namespace maplesu::su_auth::detail {
KModErr sdk_add(const char*, ResultV1&) { assert(false); return denied; }
KModErr sdk_remove(const char*, ResultV1&) { assert(false); return denied; }
KModErr sdk_clear(ResultV1&) { assert(false); return denied; }
KModErr sdk_get_uid_profiles(void (*)(const UidProfileV1*)) { assert(false); return denied; }
KModErr sdk_refresh_uid_profiles(ResultV1& result) {
    assert(scenario == "refresh" || scenario == "refresh_error"); ++typed_calls;
    if (scenario == "refresh_error") {
        result.commitState = static_cast<uint32_t>(Commit::Unknown);
        errno = EACCES;
        return fail(result, denied, Phase::ActionPersist);
    }
    result.commitState = static_cast<uint32_t>(Commit::Committed);
    return finish(result, KModErr::OK);
}
KModErr sdk_set_uid_profile(uint32_t uid, const char* package, bool allow, ResultV1& result) {
    assert((scenario == "revoke" || scenario == "revoke_error") && uid == full_uid && !allow && strcmp(package, "target") == 0);
    ++typed_calls;
    if (scenario == "revoke_error") {
        result.commitState = static_cast<uint32_t>(Commit::Unknown);
        errno = EACCES;
        return fail(result, denied, Phase::ActionPersist);
    }
    backend_allowed = allow;
    result.commitState = static_cast<uint32_t>(Commit::Committed);
    return finish(result, KModErr::OK);
}
}

int main(int argc, char** argv) {
    using namespace maplesu::su_auth;
    assert(argc == 2); scenario = argv[1];
    const unsigned descriptors_before = descriptor_count();
    if (scenario == "owner_add" || scenario == "owner_remove" || scenario == "owner_clear" || scenario == "unusable_identity") {
        errno = 0;
        const auto result = scenario == "owner_add" ? default_modules::module_su_redirect::add_su_allowlist("target")
            : scenario == "owner_remove" ? default_modules::module_su_redirect::remove_su_allowlist("target")
            : default_modules::module_su_redirect::clear_su_allowlist();
        assert(result == KModErr::ERR_MODULE_TEST_CHANNEL && errno == EOPNOTSUPP);
        assert(root_calls == 0 && storage_calls == 0 && typed_calls == 0 && relabels == 0);
    } else if (scenario == "refresh" || scenario == "refresh_error" || scenario == "revoke" || scenario == "revoke_error" || scenario == "root_denied") {
        const bool revoke = scenario == "revoke" || scenario == "revoke_error";
        const bool failure = scenario == "root_denied" || scenario == "refresh_error" || scenario == "revoke_error";
        ResultV1 result(revoke ? Operation::Remove : Operation::Refresh);
        errno = 0;
        const auto error = revoke ? detail::transact_set_uid_profile(full_uid, "target", false, result)
            : detail::transact_add(scenario == "root_denied" ? "target" : nullptr, result);
        if (failure) {
            assert(error == denied && errno == EACCES && result.resultCode == to_num(denied));
            assert(result.primaryPhase == static_cast<uint32_t>(scenario == "root_denied" ? Phase::TransactionRoot : Phase::ActionPersist));
            assert(result.commitState == static_cast<uint32_t>(scenario == "root_denied" ? Commit::NotCommitted : Commit::Unknown));
            assert(backend_allowed);
        } else {
            assert(error == KModErr::OK && result.resultCode == 0 && result.primaryPhase == 0);
            assert(result.commitState == static_cast<uint32_t>(Commit::Committed));
            assert(backend_allowed == !revoke);
        }
        assert(root_calls == unsigned(scenario == "root_denied") && typed_calls == unsigned(scenario != "root_denied"));
        assert(storage_calls == 0 && relabels == 0);
    } else {
        assert(mkdir(directory, 0755) == 0);
        const bool already = scenario == "already";
        const char* initial_helper = already ? helper : legacy_helper;
        if (already) assert(mkdir(private_directory, 0700) == 0);
        if (scenario != "missing") {
            if (scenario == "symlink") assert(symlink("/bin/sh", initial_helper) == 0);
            else {
                const int fd = open(initial_helper, O_CREAT | O_WRONLY | O_EXCL, 0777);
                assert(fd >= 0 && write(fd, "\177ELFfixture", 11) == 11); assert(close(fd) == 0);
                assert(chmod(initial_helper, already ? 0711 : 0777) == 0);
                if (scenario == "foreign_owner") assert(chown(initial_helper, 10315, 10315) == 0);
            }
        }
        struct stat ds{}, ps{}, fs{}; assert(stat(directory, &ds) == 0);
        labels[ds.st_ino] = scenario == "directory_label" ? "u:object_r:system_data_file:s0" : context;
        if (lstat(initial_helper, &fs) == 0) labels[fs.st_ino] = already ? context : "u:object_r:system_data_file:s0";
        if (already) {
            assert(chmod(directory, 0711) == 0 && stat(private_directory, &ps) == 0);
            labels[ps.st_ino] = context;
        }
        const auto result = maplesu_prepare_su_exec();
        const bool reject = scenario == "missing" || scenario == "symlink" || scenario == "foreign_owner" ||
            scenario == "relabel_denied" || scenario == "bad_readback" || scenario == "storage_denied";
        assert(reject ? is_failed(result) : !is_failed(result));
        if (scenario == "storage_denied") assert(result == denied && errno == EACCES);
        if (!reject) {
            assert(stat(helper, &fs) == 0 && stat(directory, &ds) == 0 && stat(private_directory, &ps) == 0);
            assert(labels[fs.st_ino] == context && labels[ds.st_ino] == context && labels[ps.st_ino] == context);
            assert((fs.st_mode & 07777) == 0700 && (ds.st_mode & 07777) == 0711 && (ps.st_mode & 07777) == 0700);
            assert(fs.st_uid == 0 && ps.st_uid == 0 && ps.st_gid == 0 && access(legacy_helper, F_OK) == -1);
            if (already) assert(relabels == 0);
        }
        assert(root_calls == 0 && typed_calls == 0 && storage_calls == 1);
        unlink(legacy_helper); unlink(helper);
        if (scenario != "storage_denied") assert(rmdir(private_directory) == 0);
        assert(rmdir(directory) == 0);
    }
    assert(descriptor_count() == descriptors_before);
    printf("PASS %s current-production-context/transaction; no raw Owner grant\n", argv[1]);
}
