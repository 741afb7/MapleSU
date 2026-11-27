// Exercise the unchanged production TU; stub all patch and process-mutation boundaries.
#include <array>
#include <cerrno>
#include <cstdarg>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include "module_root.cpp"

namespace {
constexpr uint64_t kFull = (uint64_t{1} << 41) - 1;
struct Fixture {
    kernel_module::caps_info caps{kFull, kFull, kFull, kFull, kFull};
    bool ambient = true;
    KModErr root_error = KModErr::OK, read_error = KModErr::OK;
    KModErr channel_error = KModErr::OK, owner_caps_error = KModErr::OK;
    uint64_t maximum = kFull;
    uint64_t channel_capabilities = MAPLESU_CAP_CREDENTIAL | MAPLESU_CAP_TASK_STATE |
        MAPLESU_CAP_PROCATTR;
    uid_t uid = 0;
    int seccomp = 0, securebits = 0;
    bool fail_second_offsets = false;
    bool fail_open = false;
    unsigned snapshots = 0, legacy_reads = 0, maxima = 0, controls = 0;
    unsigned roots = 0, offsets = 0, symbols = 0, hooks = 0;
    bool owner = false, managed_channel = false;
    KModErr owner_root_error = KModErr::OK, owner_task_error = KModErr::OK;
    kernel_module::RequestManagementReply owner_root_reply{}, owner_task_reply{};
    maplesu_caps_v3 owner_caps_value{};
    unsigned credential_channels = 0, owner_channels = 0, owner_roots = 0, owner_tasks = 0;
    unsigned owner_caps_calls = 0;
} fixture;
}

namespace maplesu::storage {
KModErr current_context(StorageContext&) { return KModErr::ERR_MODULE_PARAM; }
}

extern "C" uid_t __wrap_getuid() { return fixture.uid; }
extern "C" pid_t __wrap_setsid() { return 1; }
extern "C" int __wrap_setpgid(pid_t, pid_t) { return 0; }
extern "C" __sighandler_t __wrap_signal(int, __sighandler_t handler) { return handler; }
extern "C" int __real_open(const char*, int, ...);
extern "C" int __wrap_open(const char*, int flags, ...) {
    if (fixture.fail_open) { errno = EACCES; return -1; }
    return __real_open("/dev/null", flags);
}
extern "C" int __wrap_setns(int, int) { return 0; }
extern "C" int __wrap_unshare(int) { return 0; }
extern "C" int __wrap_access(const char*, int) { errno = ENOENT; return -1; }
extern "C" DIR* __real_opendir(const char*);
extern "C" DIR* __wrap_opendir(const char* path) { return __real_opendir(path); }
extern "C" int __wrap_prctl(int option, ...) {
    ++fixture.controls;
    return option == PR_GET_SECCOMP ? fixture.seccomp :
        option == PR_GET_SECUREBITS ? fixture.securebits : -1;
}

namespace kernel_module {
CleanupReceiptScope::CleanupReceiptScope() noexcept : saved_errno_(errno), saved_process_(0), saved_reply_{} {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept = default;
uint64_t maplesu_request_auth_state() { return fixture.owner ? 1 : 0; }
KModErr maplesu_request_credential_channel(bool& managed, uint64_t& capabilities) {
    ++fixture.credential_channels;
    managed = fixture.managed_channel || fixture.owner;
    capabilities = is_failed(fixture.channel_error) ? 0 : fixture.channel_capabilities;
    return fixture.channel_error;
}
KModErr maplesu_request_owner_channel(maplesu_metadata_owner_v3& metadata,
    uint64_t& descriptor, RequestOwnerBinding& binding) {
    ++fixture.owner_channels;
    metadata = {};
    metadata.common.capabilities = MAPLESU_CAP_TASK_STATE;
    descriptor = 0x1000;
    binding = {};
    return KModErr::OK;
}
KModErr maplesu_request_owner_root() {
    ++fixture.owner_roots;
    if (fixture.owner_root_reply.completed && fixture.owner_root_reply.status < 0)
        errno = -fixture.owner_root_reply.status;
    return fixture.owner_root_error;
}
KModErr maplesu_request_owner_caps(const maplesu_caps_v3& caps) {
    ++fixture.owner_caps_calls;
    fixture.owner_caps_value = caps;
    return fixture.owner_caps_error;
}
KModErr maplesu_request_owner_task_state() {
    ++fixture.owner_tasks;
    return fixture.owner_task_error;
}
RequestManagementReply maplesu_request_management_reply() {
    return fixture.owner_tasks ? fixture.owner_task_reply : fixture.owner_root_reply;
}
KModErr maplesu_request_owner_procattr(uint32_t, const char*, uint32_t) { return KModErr::OK; }
KModErr maplesu_request_helper_fixed(uint32_t, uint32_t, uint32_t, uint64_t, RequestEarlyFixedResult&) {
    return KModErr::ERR_MODULE_TEST_CHANNEL;
}
HookCleanupReceiptScope::HookCleanupReceiptScope() noexcept = default;
HookCleanupReceiptScope::~HookCleanupReceiptScope() noexcept = default;
RequestManagementReply maplesu_hook_reply() { return {}; }
KModErr maplesu_hook_close() { return KModErr::OK; }
KModErr maplesu_hook_release(uint64_t) { return KModErr::OK; }
KModErr get_current_caps_snapshot(caps_info& out, bool& ambient) {
    ++fixture.snapshots;
    out = fixture.caps;
    ambient = fixture.ambient;
    return fixture.read_error;
}
KModErr get_current_caps(caps_info& out) { ++fixture.legacy_reads; out = fixture.caps; return fixture.read_error; }
int get_caps_cnt() { ++fixture.legacy_reads; return fixture.ambient ? 5 : 4; }
uint64_t get_cap_ability_max() { ++fixture.maxima; return fixture.maximum; }
KModErr get_task_struct_cred_offset(uint32_t& offset) {
    ++fixture.offsets; offset = 512;
    return fixture.fail_second_offsets && fixture.offsets > 1 ?
        KModErr::ERR_MODULE_OFFSET_NOT_FOUND : KModErr::OK;
}
KModErr get_cred_uid_offset(uint32_t& out) { out = 4; return KModErr::OK; }
KModErr get_cred_euid_offset(uint32_t& out) { out = 20; return KModErr::OK; }
KModErr get_cred_cap_inheritable_offset(uint32_t& out) { out = 48; return KModErr::OK; }
KModErr get_task_struct_seccomp_offset(uint32_t& out) { out = 1024; return KModErr::OK; }
KModErr maplesu_resolve_root_credential_offsets(uint32_t& task, uint32_t& uid, uint32_t& euid,
    uint32_t& seccomp, uint32_t& inheritable) {
    const auto error = get_task_struct_cred_offset(task);
    uid = 4; euid = 20; seccomp = 1024; inheritable = 48;
    return error;
}
KModErr maplesu_hook_legacy_boot_selected(bool& selected) { selected = false; return KModErr::OK; }
KModErr set_current_process_name(const char*) { return KModErr::OK; }
KModErr get_avc_denied_kaddr(uint64_t& entry, uint64_t& tail) {
    ++fixture.symbols; entry = 0x100000; tail = 0x100004; return KModErr::OK;
}
KModErr get_filldir64_addr(uint64_t& out) { ++fixture.symbols; out = 0x200000; return KModErr::OK; }
KModErr kallsyms_lookup_name(const char* name, uint64_t& out) {
    ++fixture.symbols;
    if (!strcmp(name, "hbt:filldir64") || !strcmp(name, "iterate_dir")) {
        out = 0; return KModErr::ERR_MODULE_SYMBOL_NOT_EXIST;
    }
    out = 0x300000; return KModErr::OK;
}
KModErr kallsyms_lookup_name(const char* name, SymbolHit& out, SymbolMatchMode) {
    ++fixture.symbols; out.addr = 0x400000;
    std::strncpy(out.name, name, sizeof(out.name) - 1);
    return KModErr::OK;
}
}

namespace default_modules::module_root {
PatchBase::PatchBase(uint32_t, uint32_t, uint32_t, uint32_t) {}
PatchBase::~PatchBase() = default;
PatchCurrent::PatchCurrent(const PatchBase&) : PatchBase(0, 0, 0, 0) {}
PatchCurrent::~PatchCurrent() = default;
KModErr PatchCurrent::set_current_root(uint32_t) { ++fixture.roots; return fixture.root_error; }
KModErr PatchCurrent::set_current_caps(const kernel_module::caps_info&) { return KModErr::OK; }
PatchAvcDenied::PatchAvcDenied(const PatchBase&, const uint64_t&) : PatchBase(0, 0, 0, 0) {}
PatchAvcDenied::~PatchAvcDenied() = default;
KModErr PatchAvcDenied::patch_avc_denied() { ++fixture.hooks; return KModErr::OK; }
PatchFilldir64::PatchFilldir64(const PatchBase&, uint64_t) : PatchBase(0, 0, 0, 0) {}
PatchFilldir64::~PatchFilldir64() = default;
KModErr PatchFilldir64::patch_filldir64(const std::vector<std::string>&) { ++fixture.hooks; return KModErr::OK; }
PatchCompatFilldir::PatchCompatFilldir(const PatchBase&, uint64_t) : PatchBase(0, 0, 0, 0) {}
PatchCompatFilldir::~PatchCompatFilldir() = default;
KModErr PatchCompatFilldir::patch_compat_filldir(const std::vector<std::string>&) { ++fixture.hooks; return KModErr::OK; }
PatchAuditLogStart::PatchAuditLogStart(const PatchBase&, const uint64_t&) : PatchBase(0, 0, 0, 0) {}
PatchAuditLogStart::~PatchAuditLogStart() = default;
KModErr PatchAuditLogStart::patch_audit_log_start() { ++fixture.hooks; return KModErr::OK; }
PatchMtkHbtFilldir64::PatchMtkHbtFilldir64(const PatchBase&, uint64_t) : PatchBase(0, 0, 0, 0) {}
PatchMtkHbtFilldir64::~PatchMtkHbtFilldir64() = default;
KModErr PatchMtkHbtFilldir64::generate_hook_fake_filldir64(const std::vector<std::string>&, uint64_t&) { ++fixture.hooks; return KModErr::OK; }
KModErr PatchMtkHbtFilldir64::generate_hook_fake_filldir64_owned(const std::vector<std::string>&,
    uint64_t&, kernel_module::OwnedHookActor&) { ++fixture.hooks; return KModErr::OK; }
PatchIterateDir::PatchIterateDir(const PatchBase&, uint64_t) : PatchBase(0, 0, 0, 0) {}
PatchIterateDir::~PatchIterateDir() = default;
KModErr PatchIterateDir::patch_iterate_dir(uint64_t, uint64_t) { ++fixture.hooks; return KModErr::OK; }
KModErr PatchIterateDir::patch_iterate_dir_owned(uint64_t, uint64_t,
    const kernel_module::OwnedHookActor&, bool&) { ++fixture.hooks; return KModErr::OK; }
}

int main(int argc, char** argv) {
    struct Case { const char* name; void (*setup)(); KModErr expected; bool advance = false; bool only_print = true; };
    const KModErr danger = KModErr::ERR_MODULE_CRED_DANGER;
    const std::array<Case, 21> cases{{
        {"five-complete", [] {}, KModErr::OK, true},
        {"four-complete", [] { fixture.ambient = false; fixture.caps.ambient = 0; }, KModErr::OK, true},
        {"four-ignores-unexposed-ambient", [] { fixture.ambient = false; fixture.caps.ambient = 1; }, KModErr::OK, true},
        {"ambient-zero", [] { fixture.caps.ambient = 0; }, danger},
        {"uid-nonroot", [] { fixture.uid = 2000; }, danger},
        {"inh-mismatch", [] { fixture.caps.inheritable = 0; }, danger},
        {"prm-mismatch", [] { fixture.caps.permitted = 0; }, danger},
        {"eff-mismatch", [] { fixture.caps.effective = 0; }, danger},
        {"bnd-mismatch", [] { fixture.caps.bounding = 0; }, danger},
        {"open-error", [] { fixture.read_error = KModErr::ERR_MODULE_OPEN_FILE; }, KModErr::ERR_MODULE_OPEN_FILE},
        {"read-error", [] { fixture.read_error = KModErr::ERR_MODULE_READ_FILE; }, KModErr::ERR_MODULE_READ_FILE},
        {"parse-error", [] { fixture.read_error = KModErr::ERR_MODULE_GET_CAPS; }, KModErr::ERR_MODULE_GET_CAPS},
        {"zero-maximum", [] { fixture.maximum = 0; }, KModErr::ERR_MODULE_CAP_ABILITY},
        {"zero-caps-zero-maximum", [] { fixture.maximum = 0; fixture.caps = {}; }, KModErr::ERR_MODULE_CAP_ABILITY},
        {"seccomp-active", [] { fixture.seccomp = 2; }, danger},
        {"seccomp-read-error", [] { fixture.seccomp = -1; }, danger},
        {"securebits-active", [] { fixture.securebits = 1; }, danger},
        {"securebits-read-error", [] { fixture.securebits = -1; }, danger},
        {"root-error", [] { fixture.root_error = KModErr::ERR_MODULE_NO_ROOT; }, KModErr::ERR_MODULE_NO_ROOT},
        {"post-precheck-offset-error", [] { fixture.fail_second_offsets = true; }, KModErr::ERR_MODULE_OFFSET_NOT_FOUND, true},
        {"normal-hook-flow", [] {}, KModErr::OK, true, false},
    }};
    unsigned failures = 0;
    const bool owner_only = argc > 1 && !std::strcmp(argv[1], "owner-only");
    if (!owner_only) for (const auto& test : cases) {
        fixture = {};
        test.setup();
        const KModErr actual = default_modules::module_root::init_anti_detect_root_env({"fixture"}, test.only_print);
        const bool failed_root = is_failed(fixture.root_error);
        const bool failed_read = is_failed(fixture.read_error);
        const bool shape = fixture.roots == 1 && fixture.legacy_reads == 0 &&
            fixture.snapshots == (failed_root ? 0U : 1U) &&
            fixture.maxima == (failed_root || failed_read ? 0U : 1U) &&
            fixture.controls == (failed_root || failed_read || fixture.maximum == 0 ? 0U : 2U) &&
            fixture.offsets == (test.advance ? 2U : 1U) &&
            fixture.symbols == (test.advance && !fixture.fail_second_offsets ? 6U : 0U) &&
            fixture.hooks == (test.only_print ? 0U : 4U);
        const bool passed = actual == test.expected && shape;
        failures += !passed;
        printf("PRECHECK case=%s passed=%d error=%zd expected=%zd snapshot=%u legacy=%u maximum=%u control=%u offsets=%u symbols=%u hooks=%u\n",
            test.name, passed, to_num(actual), to_num(test.expected), fixture.snapshots,
            fixture.legacy_reads, fixture.maxima, fixture.controls, fixture.offsets, fixture.symbols, fixture.hooks);
    }
    fixture = {};
    fixture.managed_channel = true;
    fixture.owner_root_reply = {};
    fixture.owner_root_reply.effects = MAPLESU_EFFECT_COMMITTED;
    fixture.owner_root_reply.command = MAPLESU_REQUEST_CRED_ROOT;
    fixture.owner_root_reply.completed = true;
    fixture.owner_task_reply = {};
    fixture.owner_task_reply.effects = MAPLESU_EFFECT_TASK_STATE_COMPLETED;
    fixture.owner_task_reply.command = MAPLESU_REQUEST_TASK_STATE;
    fixture.owner_task_reply.completed = true;
    const auto key_managed_error = default_modules::module_root::set_current_root(false);
    const auto key_managed = default_modules::module_root::current_root_transition();
    const bool key_managed_ok = key_managed_error == KModErr::OK && key_managed.credentials_managed &&
        key_managed.credentials_committed && key_managed.task_state_attempted &&
        key_managed.task_state.completed && fixture.credential_channels == 1 &&
        fixture.owner_roots == 1 && fixture.owner_tasks == 1 && fixture.roots == 0;
    failures += !key_managed_ok;
    printf("KEY managed-root connected=%d channels=%u roots=%u tasks=%u legacy=%u\n",
        key_managed_ok, fixture.credential_channels, fixture.owner_roots, fixture.owner_tasks, fixture.roots);

    fixture = {};
    fixture.owner = true;
    fixture.channel_capabilities = MAPLESU_CAP_TASK_STATE;
    errno = 0;
    const auto missing_capability_error = default_modules::module_root::set_current_root(false);
    const auto missing_capability = default_modules::module_root::current_root_transition();
    const bool missing_capability_ok = missing_capability_error == KModErr::ERR_MODULE_TEST_CHANNEL &&
        missing_capability.credentials_managed && !missing_capability.credentials_attempted &&
        missing_capability.task_state_error == KModErr::ERR_MODULE_TEST_CHANNEL && errno == EOPNOTSUPP &&
        fixture.credential_channels == 1 && !fixture.owner_roots && !fixture.owner_tasks && !fixture.roots;
    failures += !missing_capability_ok;
    printf("OWNER missing-capability blocked=%d channels=%u roots=%u tasks=%u\n",
        missing_capability_ok, fixture.credential_channels, fixture.owner_roots, fixture.owner_tasks);

    fixture = {};
    fixture.owner = true;
    fixture.channel_error = KModErr::ERR_MODULE_OPEN_FILE;
    errno = EIO;
    const auto channel_error = default_modules::module_root::set_current_root(false);
    const auto channel_failure = default_modules::module_root::current_root_transition();
    const bool channel_error_ok = channel_error == KModErr::ERR_MODULE_OPEN_FILE &&
        channel_failure.credentials_error == KModErr::ERR_MODULE_OPEN_FILE && errno == EIO &&
        fixture.credential_channels == 1 && !fixture.owner_roots && !fixture.owner_tasks && !fixture.roots;
    failures += !channel_error_ok;
    printf("OWNER channel-error preserved=%d channels=%u roots=%u tasks=%u\n",
        channel_error_ok, fixture.credential_channels, fixture.owner_roots, fixture.owner_tasks);

    fixture = {};
    fixture.managed_channel = true;
    const kernel_module::caps_info requested_caps{1, 3, 2, 7, 1};
    const auto managed_caps_error = default_modules::module_root::set_current_caps(requested_caps);
    const auto& managed_caps = fixture.owner_caps_value;
    const bool managed_caps_ok = managed_caps_error == KModErr::OK && fixture.credential_channels == 1 &&
        fixture.owner_caps_calls == 1 && managed_caps.inheritable == requested_caps.inheritable &&
        managed_caps.permitted == requested_caps.permitted && managed_caps.effective == requested_caps.effective &&
        managed_caps.bounding == requested_caps.bounding && managed_caps.ambient == requested_caps.ambient &&
        fixture.offsets == 0;
    failures += !managed_caps_ok;
    printf("KEY managed-caps connected=%d channels=%u caps=%u offsets=%u\n",
        managed_caps_ok, fixture.credential_channels, fixture.owner_caps_calls, fixture.offsets);

    fixture = {};
    fixture.owner = true;
    fixture.fail_open = true;
    fixture.owner_root_reply = {};
    fixture.owner_root_reply.effects = MAPLESU_EFFECT_COMMITTED;
    fixture.owner_root_reply.command = MAPLESU_REQUEST_CRED_ROOT;
    fixture.owner_root_reply.completed = true;
    fixture.owner_task_reply = {};
    fixture.owner_task_reply.effects = MAPLESU_EFFECT_TASK_STATE_COMPLETED;
    fixture.owner_task_reply.command = MAPLESU_REQUEST_TASK_STATE;
    fixture.owner_task_reply.completed = true;
    errno = 0;
    const auto environment_error = default_modules::module_root::set_current_root(false);
    const auto environment_failure = default_modules::module_root::current_root_transition();
    const bool environment_error_ok = environment_error == KModErr::ERR_MODULE_OPEN_FILE && errno == EACCES &&
        environment_failure.credentials_committed && environment_failure.credentials.completed &&
        environment_failure.credentials.effects == MAPLESU_EFFECT_COMMITTED &&
        !environment_failure.environment_completed && fixture.owner_roots == 1 && fixture.owner_tasks == 1;
    failures += !environment_error_ok;
    printf("OWNER environment-error-after-commit preserved=%d errno=%d roots=%u tasks=%u\n",
        environment_error_ok, errno, fixture.owner_roots, fixture.owner_tasks);

    fixture = {};
    fixture.owner = true;
    fixture.owner_root_reply = {};
    fixture.owner_root_reply.status = -ESTALE;
    fixture.owner_root_reply.effects = MAPLESU_EFFECT_COMMITTED;
    fixture.owner_root_reply.command = MAPLESU_REQUEST_CRED_ROOT;
    fixture.owner_root_reply.completed = true;
    fixture.owner_root_error = KModErr::ERR_MODULE_ROOT_KEY;
    const auto post_commit_error = default_modules::module_root::set_current_root(false);
    const auto post_commit = default_modules::module_root::current_root_transition();
    const bool post_commit_preserved = post_commit_error == KModErr::ERR_MODULE_ROOT_KEY &&
        post_commit.credentials_attempted && post_commit.credentials_committed &&
        post_commit.credentials.completed && post_commit.credentials.command == MAPLESU_REQUEST_CRED_ROOT &&
        post_commit.credentials.effects == MAPLESU_EFFECT_COMMITTED &&
        post_commit.credentials.status == -ESTALE && post_commit.system_error == ESTALE &&
        errno == ESTALE && fixture.owner_roots == 1 && !fixture.owner_tasks && !fixture.roots;
    failures += !post_commit_preserved;
    printf("OWNER post-commit-error preserved=%d roots=%u tasks=%u\n",
        post_commit_preserved, fixture.owner_roots, fixture.owner_tasks);

    fixture = {};
    fixture.owner = true;
    fixture.owner_root_reply = {};
    fixture.owner_root_reply.effects = MAPLESU_EFFECT_COMMITTED;
    fixture.owner_root_reply.command = MAPLESU_REQUEST_CRED_ROOT;
    fixture.owner_root_reply.completed = true;
    fixture.owner_task_reply = {};
    fixture.owner_task_reply.status = -1;
    fixture.owner_task_reply.effects = MAPLESU_EFFECT_TASK_STATE_COMPLETED;
    fixture.owner_task_reply.command = MAPLESU_REQUEST_TASK_STATE;
    fixture.owner_task_reply.completed = true;
    fixture.owner_task_error = KModErr::ERR_MODULE_TEST_CHANNEL;
    const auto task_error = default_modules::module_root::set_current_root(false);
    const auto task_failure = default_modules::module_root::current_root_transition();
    const bool task_post_error_preserved = task_error == KModErr::ERR_MODULE_TEST_CHANNEL &&
        task_failure.credentials_committed && task_failure.credentials.effects == MAPLESU_EFFECT_COMMITTED &&
        task_failure.task_state_attempted && task_failure.task_state_error == KModErr::ERR_MODULE_TEST_CHANNEL &&
        fixture.owner_roots == 1 && fixture.owner_tasks == 1;
    failures += !task_post_error_preserved;
    printf("OWNER task-post-error preserved=%d roots=%u tasks=%u\n",
        task_post_error_preserved, fixture.owner_roots, fixture.owner_tasks);
    printf("{\"case\":\"m070-precheck\",\"cases\":%zu,\"failures\":%u,\"passed\":%s}\n",
        cases.size(), failures, failures ? "false" : "true");
    return failures ? 1 : 0;
}
