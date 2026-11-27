#ifndef SOURCE_UNDER_TEST
#define SOURCE_UNDER_TEST "../ultra/modules/module_control.cpp"
#endif
#include SOURCE_UNDER_TEST
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <sys/mman.h>

namespace fs = std::filesystem;
static std::string fixture;
static std::vector<maplesu::sdk::module_record> records;
static unsigned cases, failures;
static uint64_t auth_state = 1, generation;
static pid_t parent_pid, session_process;
static std::string failure;
static bool fail_close;
struct Calls { unsigned admitted, forks, rooted, closed; };
static Calls* calls;
extern "C" pid_t __real_fork();
extern "C" pid_t __wrap_fork() {
    if (getpid() != parent_pid) abort();
    ++calls->forks;
    return __real_fork();
}
extern "C" void maplesu_system_directory(std::string* out) {
    maplesu::storage::StorageContext context;
    if (getpid() == parent_pid || is_failed(maplesu::storage::current_context(context))) abort();
    *out = fixture;
}
extern "C" void maplesu_installed_helper_path(std::string* out) { *out = fixture + "/helper"; }
namespace default_modules::module_root {
KModErr set_current_root(bool manager) {
    if (getpid() == parent_pid || manager == kernel_module::maplesu_request_helper_mode()) abort();
    ++calls->rooted;
    if (failure == "root") return KModErr::ERR_MODULE_ROOT_KEY;
    session_process = getpid();
    ++generation;
    if (failure == "root_after_open") return KModErr::ERR_MODULE_ROOT_KEY;
    return KModErr::OK;
}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state() { return auth_state; }
KModErr maplesu_request_owner_channel(maplesu_metadata_owner_v3&, uint64_t&, RequestOwnerBinding&) {
    if (getpid() != parent_pid || !maplesu_request_manager_mode()) abort();
    ++calls->admitted;
    if (failure == "admission_denied") return KModErr::ERR_MODULE_ROOT_KEY;
    return failure == "admission_transport" ? KModErr::ERR_MODULE_TEST_CHANNEL : KModErr::OK;
}
KModErr maplesu_request_helper_vendor(uint64_t action, unsigned index, maplesu_installation_state_v3&) {
    if (getpid() != parent_pid || !maplesu_request_helper_mode() || action != MAPLESU_INSTALLATION_READ || index) abort();
    ++calls->admitted;
    return KModErr::OK;
}
bool maplesu_request_owner_binding_matches(const RequestOwnerBinding& binding) {
    return failure != "identity" && binding.auth_state == auth_state &&
        binding.process == uint64_t(getpid()) && binding.actual_uid == 0 && session_process == getpid();
}
bool maplesu_request_owner_lifetime_capture(RequestOwnerLifetimeToken& token) {
    if (session_process != getpid()) return false;
    token.binding_.auth_state = auth_state;
    token.binding_.process = getpid();
    token.session_ = generation;
    return true;
}
bool maplesu_request_owner_lifetime_matches(const RequestOwnerLifetimeToken& token) {
    return token.session_ == generation && maplesu_request_owner_binding_matches(token.binding_);
}
KModErr maplesu_request_owner_close() {
    if (getpid() == parent_pid) abort();
    ++calls->closed;
    const bool active = session_process == getpid();
    session_process = 0;
    return active && fail_close ? KModErr::ERR_MODULE_BOOT_SESSION : KModErr::OK;
}
KModErr maplesu_request_helper_open() { session_process = getpid(); ++generation; return KModErr::OK; }
KModErr maplesu_request_owner_deployment(RequestDeploymentNamespace& out) {
    if (failure == "deployment") return KModErr::ERR_MODULE_TEST_CHANNEL;
    if (session_process != getpid()) return KModErr::ERR_MODULE_ROOT_KEY;
    memcpy(out.record.name, "0123456789abcdef", 16);
    out.binding.auth_state = auth_state;
    out.binding.process = getpid();
    out.binding.actual_uid = getuid();
    return KModErr::OK;
}
CleanupReceiptScope::CleanupReceiptScope() noexcept
    : saved_errno_(errno), saved_process_(getpid()), saved_reply_{} {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept { errno = saved_errno_; }
}
namespace maplesu::sdk {
MapleSUEnvState get_maplesu_environment_state() {
    return failure == "environment" ? MapleSUEnvState::Fault : MapleSUEnvState::Running;
}
KModErr unsafe_get_all_modules(std::vector<module_record>& out) {
    maplesu::storage::StorageContext context;
    if (is_failed(maplesu::storage::current_context(context))) abort();
    out = records;
    return KModErr::OK;
}
}
static void check(bool passed, const char* label, size_t length = 0) {
    ++cases;
    if (!passed) { ++failures; std::printf("FAIL %s length=%zu\n", label, length); }
}
static maplesu::sdk::module_record record(const std::string& id) {
    maplesu::sdk::module_record row{};
    memcpy(row.desc.id32, id.data(), id.size());
    row.state = maplesu::sdk::ModuleRunState::Running;
    return row;
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    parent_pid = getpid();
    calls = static_cast<Calls*>(mmap(nullptr, sizeof(Calls), PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS, -1, 0));
    if (calls == MAP_FAILED) return 2;
    *calls = {};
    char directory[] = "/tmp/maplesu-control-id-XXXXXX";
    const auto created = mkdtemp(directory);
    if (!created) return 2;
    fixture = created;
    fs::create_directories(fixture + "/modules");
    fs::copy_file(argv[1], fixture + "/helper");
    const std::vector<std::string> valid = {"a", "p0_probe", "com.module.test", std::string(32, 'z'),
        std::string(33, 'x'), std::string(255, 'x'), std::string(1023, 'x'), u8"模块", u8"module😀",
        "module with space", "module:colon", "module\\backslash"};
    for (const auto& id : valid) {
        check(id_valid(id.c_str()), "admission", id.size());
        records.clear();
        maplesu::ModuleControlState out;
        check(maplesu::get_module_control(id.c_str(), out) == maplesu::MODULE_CONTROL_NOT_FOUND,
              "valid missing record", id.size());
        if (id.size() > 255) continue;  // The real filesystem has a separate NAME_MAX limit.
        fs::create_directories(fixture + "/modules/" + id);
        records = {record(id)};
        check(maplesu::get_module_control(id.c_str(), out) == 0 && out.enabled,
              "exact record read", id.size());
        check(maplesu::set_module_enabled(id.c_str(), false, out) == 0 && !out.enabled && out.needsReboot,
              "persist and readback", id.size());
        const auto replies = maplesu::get_module_controls(records);
        check(replies.size() == 1 && replies[0].error == 0 && !replies[0].state.enabled,
              "list record read", id.size());
        records.push_back(records.front());
        check(maplesu::get_module_control(id.c_str(), out) == maplesu::MODULE_CONTROL_IO,
              "duplicate full identity", id.size());
    }
    for (const auto& id : std::vector<std::string>{"", ".", "..", "a/b", "../bad", std::string(1024, 'x')}) {
        check(!id_valid(id.c_str()), "invalid admission", id.size());
        maplesu::ModuleControlState out;
        check(maplesu::get_module_control(id.c_str(), out) == maplesu::MODULE_CONTROL_INVALID_ID,
              "invalid public read", id.size());
    }
    check(!id_valid(nullptr), "null id");
    const size_t page = sysconf(_SC_PAGESIZE);
    void* guarded = mmap(nullptr, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (guarded == MAP_FAILED || mprotect(static_cast<char*>(guarded) + page, page, PROT_NONE)) return 2;
    char* unterminated = static_cast<char*>(guarded) + page - 1024;
    memset(unterminated, 'x', 1024);
    check(!id_valid(unterminated), "bounded nonterminated descriptor");
    maplesu::sdk::module_record malformed{};
    memcpy(malformed.desc.id32, unterminated, 1024);
    const std::string short_id(32, 'z'), long_id(33, 'z');
    fs::create_directories(fixture + "/modules/" + short_id);
    fs::create_directories(fixture + "/modules/" + long_id);
    records = {malformed, record(long_id), record(short_id)};
    maplesu::ModuleControlState out;
    check(maplesu::get_module_control(short_id.c_str(), out) == 0,
          "same 32 byte prefix matches short exact record");
    check(maplesu::get_module_control(long_id.c_str(), out) == 0,
          "same 32 byte prefix matches long exact record");
    records = {malformed};
    check(maplesu::get_module_control(short_id.c_str(), out) == maplesu::MODULE_CONTROL_NOT_FOUND,
          "unterminated record is not a prefix match");
    records = {record(short_id)};
    for (const auto& item : std::vector<std::pair<std::string, KModErr>>{
             {"root", KModErr::ERR_MODULE_ROOT_KEY},
             {"deployment", KModErr::ERR_MODULE_TEST_CHANNEL},
             {"identity", KModErr::ERR_MODULE_ROOT_KEY}}) {
        failure = item.first;
        check(maplesu::get_module_control(short_id.c_str(), out) == static_cast<int64_t>(item.second),
              "worker authentication failure stays failed");
        const auto rows = maplesu::get_module_controls(records);
        check(rows.size() == 1 && rows[0].error == static_cast<int64_t>(item.second),
              "list authentication failure stays attached");
        check(maplesu::set_module_enabled(short_id.c_str(), false, out) == static_cast<int64_t>(item.second),
              "mutation authentication failure stays failed");
    }
    failure.clear();
    for (const auto auth : {UINT64_C(0), UINT64_C(2), UINT64_MAX}) {
        auth_state = auth;
        check(maplesu::get_module_control(short_id.c_str(), out) == static_cast<int64_t>(KModErr::ERR_MODULE_ROOT_KEY),
              "invalid authentication tag has no Key fallback");
    }
    auth_state = 3;
    check(maplesu::get_module_control(short_id.c_str(), out) == 0, "authenticated helper uses its worker scope");
    auth_state = 9;
    session_process = parent_pid;
    check(maplesu::get_module_control(short_id.c_str(), out) == 0, "fork child opens its own manager session");
    check(auth_state == 9 && session_process == parent_pid, "worker never changes parent identity or session");
    const auto state_file = fs::path(fixture) / "modules" / short_id / ".maplesu-state";
    const auto contents = [&]() {
        std::ifstream input(state_file, std::ios::binary);
        return std::string(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
    };
    for (const auto& item : std::vector<std::pair<std::string, KModErr>>{
             {"admission_denied", KModErr::ERR_MODULE_ROOT_KEY},
             {"admission_transport", KModErr::ERR_MODULE_TEST_CHANNEL}}) {
        failure = item.first;
        const auto before = *calls;
        const auto persisted = contents();
        check(maplesu::get_module_control(short_id.c_str(), out) == static_cast<int64_t>(item.second),
              "parent admission error is returned without a worker");
        check(maplesu::set_module_enabled(short_id.c_str(), true, out) == static_cast<int64_t>(item.second) &&
              contents() == persisted, "parent admission failure leaves desired state intact");
        const auto rows = maplesu::get_module_controls({record(short_id), malformed});
        check(rows.size() == 2 && rows[0].error == static_cast<int64_t>(item.second) &&
              rows[1].error == maplesu::MODULE_CONTROL_INVALID_ID, "admission keeps invalid list row distinct");
        bool supported = true;
        check(maplesu::module_control_capability(supported) == static_cast<int64_t>(item.second) && !supported,
              "capability admission failure remains failed");
        check(calls->admitted == before.admitted + 4 && calls->forks == before.forks &&
              calls->rooted == before.rooted && calls->closed == before.closed,
              "failed admission performs no fork Root or close");
        check(maplesu::get_module_control("../bad", out) == maplesu::MODULE_CONTROL_INVALID_ID &&
              calls->admitted == before.admitted + 4, "ID failure precedes admission");
    }
    failure.clear();
    fail_close = true;
    check(maplesu::get_module_control(short_id.c_str(), out) == static_cast<int64_t>(KModErr::ERR_MODULE_BOOT_SESSION) &&
          out.toggleSupported, "read success retains owner close failure");
    auto rows = maplesu::get_module_controls({record(short_id), record("missing"), malformed});
    check(rows.size() == 3 && rows[0].error == static_cast<int64_t>(KModErr::ERR_MODULE_BOOT_SESSION) &&
          rows[1].error == maplesu::MODULE_CONTROL_NOT_FOUND && rows[2].error == maplesu::MODULE_CONTROL_INVALID_ID,
          "list close failure applies only to successful rows");
    const auto mutation = maplesu::set_module_enabled(short_id.c_str(), true, out);
    const auto committed = contents();
    check(mutation == static_cast<int64_t>(KModErr::ERR_MODULE_BOOT_SESSION) &&
          out.enabled && out.needsReboot && committed.size() == 38 && committed[0] == '1',
          "committed mutation retains close failure and its effects");
    records = {record(short_id), record(short_id)};
    check(maplesu::get_module_control(short_id.c_str(), out) == maplesu::MODULE_CONTROL_IO,
          "duplicate record first error survives close failure");
    rows = maplesu::get_module_controls({record(short_id), record(short_id), record(long_id)});
    check(rows.size() == 3 && rows[0].error == maplesu::MODULE_CONTROL_IO && rows[1].error == maplesu::MODULE_CONTROL_IO &&
          rows[2].error == static_cast<int64_t>(KModErr::ERR_MODULE_BOOT_SESSION),
          "list duplicate errors survive a later successful row close failure");
    failure = "environment";
    records = {record(short_id)};
    const auto persisted = contents();
    check(maplesu::set_module_enabled(short_id.c_str(), false, out) == maplesu::MODULE_CONTROL_ENVIRONMENT && contents() == persisted,
          "environment first error survives close failure without mutation");
    for (const auto& item : std::vector<std::pair<std::string, KModErr>>{
             {"root_after_open", KModErr::ERR_MODULE_ROOT_KEY},
             {"deployment", KModErr::ERR_MODULE_TEST_CHANNEL},
             {"identity", KModErr::ERR_MODULE_ROOT_KEY}}) {
        failure = item.first;
        check(maplesu::get_module_control(short_id.c_str(), out) == static_cast<int64_t>(item.second),
              "worker first error survives cleanup close failure");
    }
    fail_close = false;
    failure.clear();
    check(auth_state == 9 && session_process == parent_pid, "admission and cleanup failures preserve parent session");
    munmap(calls, sizeof(Calls));
    munmap(guarded, page * 2);
    fs::remove_all(fixture);
    std::printf("CONTROL_ID_CASES %u FAILURES %u\n", cases, failures);
    return failures != 0;
}
