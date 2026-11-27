#include "runtime.h"
#include "operations.h"
#include "../compat/module_runtime_legacy_abi.h"
#include "state.h"
#include "lifecycle.h"
#include "process_context.h"
#include "maplesu_storage_private.h"
#include "maplesu_storage_worker_private.h"
#include "maplesu_module_environment_private.h"
#include "module_base_kernel_request.h"
#include "maplesu_sdk/maplesu_installer.h"

#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>

extern "C" int maplesu_skip_disabled(const char*);

#ifndef MAPLESU_MODULE_DATA_ROOT
#define MAPLESU_MODULE_DATA_ROOT "/data"
#endif

namespace maplesu::module_runtime {
namespace {
using List = module_state::List;
bool contains(const std::vector<std::string>& ids, const std::string& id) {
    return std::find(ids.begin(), ids.end(), id) != ids.end();
}
void remember(KModErr error, KModErr& first) {
    if (is_ok(first) && is_failed(error)) first = error;
}
bool transfer(int fd, void* buffer, size_t length, bool writing) {
    auto* bytes = static_cast<unsigned char*>(buffer);
    while (length) {
        ssize_t count = writing ? write(fd, bytes, length) : read(fd, bytes, length);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes += count;
        length -= static_cast<size_t>(count);
    }
    return true;
}
void setup(const std::string& directory, const char* id) {
    if (setsid() < 0) setpgid(0, 0);
    signal(SIGPIPE, SIG_IGN);
    const int ns = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
    if (ns >= 0) {
        const int joined = setns(ns, CLONE_NEWNS);
        close(ns);
        if (joined == 0) unshare(CLONE_NEWNS);
    }
    (void)cg_v2::migrate_self_to_root();
    (void)cg_v1::migrate_self_threads_v1();
    (void)selinux_procattr::setcon("u:r:shell:s0");
    (void)selinux_procattr::setexeccon("u:r:shell:s0");
    prctl(PR_SET_NAME, id, 0, 0, 0);
    std::string component = directory;
    while (component.size() > 1 && component.back() == '/') component.pop_back();
    const char* old = getenv("PATH");
    std::string path = old ? old : "";
    bool found = false;
    for (size_t at = 0; at <= path.size();) {
        size_t end = path.find(':', at);
        if (end == std::string::npos) end = path.size();
        if (path.substr(at, end - at) == component) { found = true; break; }
        at = end + 1;
    }
    if (!found) setenv("PATH", (component + (path.empty() ? "" : ":" + path)).c_str(), 1);
}
Result validate(const module::Descriptor& desc, const module::SdkVersion& current) {
    if (!memchr(desc.id32, 0, sizeof(desc.id32)) || !desc.id32[0])
        return {KModErr::ERR_MODULE_PARAM, 0, false};
    const std::string directory = private_directory(desc.id32);
    if (directory.empty()) return {KModErr::ERR_MODULE_PARAM, 0, false};
    if (maplesu_skip_disabled(directory.c_str()))
        return {static_cast<KModErr>(-20150), 0, true};
    // This compatibility floor comes from the existing SDK installer contract.
    constexpr module::SdkVersion minimum{4, 0, 5};
    if (current < desc.min_sdk_ver) return {KModErr::ERR_MODULE_REQUIRE_MIN_SDK, 0, false};
    if (desc.min_sdk_ver < minimum) return {KModErr::ERR_MODULE_SDK_TOO_OLD, 0, false};
    return {};
}
KModErr publish(const std::string& id, const Result& result) {
    const bool success = is_ok(result.loader) && result.entry == 0;
    RETURN_IF_ERROR(module_state::remove(success ? List::Abnormal : List::Running, id));
    return module_state::add(success ? List::Running : List::Abnormal, id);
}
}

std::string system_directory() {
    std::string directory;
    return is_ok(storage::current_system_directory(directory)) ? directory : std::string();
}
std::string private_directory(const char* id) {
    const std::string root = system_directory();
    if (root.empty() || !id || !*id) return {};
    return root + "modules/" + id + "/";
}

namespace {
using module_lifecycle::ModuleMutation;
struct FileStamp {
    uint64_t device = 0, inode = 0;
    int64_t size = 0, modified = 0, modified_ns = 0, changed = 0, changed_ns = 0;
    uint32_t mode = 0;
    bool present = false;
};
struct ExecutionIdentity {
    FileStamp image, states[3];
};
struct Outcome {
    Result value;
    KModErr state = KModErr::OK;
    bool complete = true, has_identity = false;
    ExecutionIdentity identity;
    KModErr bootstrap_error = KModErr::OK;
};
std::string kernel_directory() {
    std::string directory;
    return is_ok(storage::current_kernel_directory(directory)) ? directory : std::string();
}
bool stamp(const std::string& path, FileStamp& out, bool follow = false) {
    struct stat info{};
    if ((follow ? stat(path.c_str(), &info) : lstat(path.c_str(), &info)) != 0) return errno == ENOENT;
    out = {static_cast<uint64_t>(info.st_dev), static_cast<uint64_t>(info.st_ino),
        info.st_size, info.st_mtim.tv_sec, info.st_mtim.tv_nsec,
        info.st_ctim.tv_sec, info.st_ctim.tv_nsec, info.st_mode, true};
    return true;
}
bool same(const FileStamp& a, const FileStamp& b) {
    return a.present == b.present && a.device == b.device && a.inode == b.inode &&
        a.size == b.size && a.modified == b.modified && a.modified_ns == b.modified_ns &&
        a.changed == b.changed && a.changed_ns == b.changed_ns && a.mode == b.mode;
}
bool identify(const char* id, ExecutionIdentity& out) {
    const std::string directory = private_directory(id);
    if (directory.empty() || !stamp(directory + id, out.image, true) || !out.image.present) return false;
    for (int n = 0; n < 3; ++n) {
        const std::string state = module_state::path(static_cast<List>(n));
        if (state.empty() || !stamp(state, out.states[n])) return false;
    }
    return true;
}
bool announce(int fd, const char* id, bool& announced) {
    ExecutionIdentity identity;
    uint8_t present = identify(id, identity) ? 1 : 0;
    announced = true;
    return transfer(fd, &present, sizeof(present), true) &&
        (!present || transfer(fd, &identity, sizeof(identity), true));
}
KModErr clear_states(const char* id) {
    for (const List state : {List::Running, List::Abnormal, List::Removed})
        RETURN_IF_ERROR(module_state::remove(state, id));
    return KModErr::OK;
}
Outcome pending_removal_state(const char* id, bool boot, bool& removed) {
    Outcome outcome;
    std::vector<std::string> ids;
    outcome.state = module_state::read(List::PendingRemoval, ids);
    if (is_failed(outcome.state)) outcome.bootstrap_error = outcome.state;
    removed = is_ok(outcome.state) && contains(ids, id);
    if (removed) {
        outcome.value.skipped = true;
        if (boot) {
            outcome.state = module_install::cleanup_pending_module(id);
            if (is_ok(outcome.state)) outcome.state = clear_states(id);
            if (is_ok(outcome.state)) outcome.state = module_state::remove(List::PendingRemoval, id);
        }
    }
    if (is_failed(outcome.state)) outcome.value.loader = outcome.state;
    return outcome;
}
Result execute_owned(const module::Descriptor& desc) {
    const std::string directory = private_directory(desc.id32);
    Result result;
    if (directory.empty()) return {KModErr::ERR_MODULE_PARAM, 0, false};
    const std::string system = system_directory();
    if (system.empty()) return {KModErr::ERR_MODULE_PARAM, 0, false};
    module_environment::Environment environment;
    const auto version = sdk::get_sdk_version();
    result.loader = module_environment::capture(version.major, version.minor, version.patch,
                                               environ, environment);
    if (is_failed(result.loader)) return result;
    const std::string override_path = system + "modules_new_state/" + desc.id32;
    if (unlink(override_path.c_str()) != 0 && errno != ENOENT)
        result.loader = KModErr::ERR_MODULE_DEL_FILE;
    setup(directory, desc.id32);
    if (is_ok(result.loader)) result.loader = environment.apply();
    if (is_ok(result.loader) && chdir(directory.c_str()) != 0)
        result.loader = KModErr::ERR_MODULE_OPEN_DIR;
    void* image = nullptr;
    if (is_ok(result.loader)) {
        image = dlopen((directory + desc.id32).c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!image) result.loader = KModErr::ERR_MODULE_DLOPEN;
    }
    if (image) {
        const auto entry = compat::native_module::resolve_export(
            [image](const char* symbol) { return dlsym(image, symbol); },
            compat::native_module::kModuleEntrySymbol, compat::native_module::kLegacyModuleEntrySymbol);
        if (!entry) result.loader = KModErr::ERR_MODULE_DLSYM;
        else result.loader = compat::invoke_module_entry(entry, kernel_module::current_module_root_key(),
                                                        directory.c_str(), result.entry);
        dlclose(image);
    }
    return result;
}
// This runs only in the existing module child. Retire its restricted helper
// session before entering a separately authenticated real-Root operation scope.
KModErr select_module_worker_identity() {
    if (!kernel_module::maplesu_request_helper_mode()) return KModErr::OK;
    {
        storage::WorkerScope helper_worker;
        RETURN_IF_ERROR(helper_worker.root());
        if (getuid() != 0 || geteuid() != 0) return KModErr::ERR_MODULE_NO_ROOT;
        RETURN_IF_ERROR(helper_worker.close_checked());
    }
    RETURN_IF_ERROR(kernel_module::maplesu_request_owner_close());
    return kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::ManagerOwner);
}

template <typename Action>
Outcome worker(const char* id, Action action) {
    auto unavailable = [](KModErr error) {
        Outcome outcome{{error, 0, false}, error};
        outcome.bootstrap_error = error;
        return outcome;
    };
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) return unavailable(admission);
    int pipe_fds[2];
    if (pipe2(pipe_fds, O_CLOEXEC) != 0) return unavailable(KModErr::ERR_MODULE_OPEN_FILE);
    fflush(nullptr);
    const pid_t child = fork();
    if (child < 0) {
        close(pipe_fds[0]); close(pipe_fds[1]);
        return unavailable(KModErr::ERR_MODULE_OPEN_FILE);
    }
    if (child == 0) {
        close(pipe_fds[0]);
        signal(SIGPIPE, SIG_IGN);
        Outcome outcome;
        bool announced = false, action_started = false;
        std::optional<storage::WorkerScope> storage_worker;
        try {
            const auto selected = select_module_worker_identity();
            storage_worker.emplace(id);
            const auto storage_error = is_failed(selected) ? selected : storage_worker->root();
            const std::string directory = kernel_directory();
            if (is_failed(storage_error) || directory.empty()) {
                outcome.state = outcome.value.loader = is_failed(storage_error) ?
                    storage_error : KModErr::ERR_MODULE_ROOT_KEY;
                outcome.bootstrap_error = outcome.state;
            } else {
                ModuleMutation mutation(directory, id);
                outcome.value.loader = mutation.error();
                if (is_ok(outcome.value.loader)) {
                    action_started = true;
                    outcome = action(pipe_fds[1], announced, *storage_worker);
                } else {
                    outcome.state = outcome.value.loader;
                    if (outcome.state != static_cast<KModErr>(MODULE_CONTROL_BUSY))
                        outcome.bootstrap_error = outcome.state;
                }
            }
        } catch (...) {
            outcome.value.loader = KModErr::ERR_MODULE_DLOPEN;
            if (!action_started) outcome.bootstrap_error = outcome.state = outcome.value.loader;
        }
        if (storage_worker) {
            const auto closed = storage_worker->close_checked();
            storage_worker.reset();
            if (is_failed(closed)) {
                if (is_ok(outcome.value.loader)) outcome.value.loader = closed;
                if (is_ok(outcome.state)) outcome.state = closed;
                if (!action_started && is_ok(outcome.bootstrap_error)) outcome.bootstrap_error = closed;
            }
        }
        uint8_t no_identity = 0, skipped = outcome.value.skipped ? 1 : 0;
        int64_t loader = static_cast<int64_t>(outcome.value.loader), state = static_cast<int64_t>(outcome.state);
        int64_t bootstrap = static_cast<int64_t>(outcome.bootstrap_error);
        const bool sent = (announced || transfer(pipe_fds[1], &no_identity, sizeof(no_identity), true)) &&
            transfer(pipe_fds[1], &loader, sizeof(loader), true) &&
            transfer(pipe_fds[1], &outcome.value.entry, sizeof(outcome.value.entry), true) &&
            transfer(pipe_fds[1], &skipped, sizeof(skipped), true) &&
            transfer(pipe_fds[1], &state, sizeof(state), true) &&
            transfer(pipe_fds[1], &bootstrap, sizeof(bootstrap), true);
        close(pipe_fds[1]);
        fflush(nullptr);
        _exit(sent ? 0 : 125);
    }
    close(pipe_fds[1]);
    Outcome outcome;
    outcome.complete = false;
    int64_t loader = 0, state = 0, bootstrap = 0;
    uint8_t has_identity = 0, skipped = 0;
    if (!transfer(pipe_fds[0], &has_identity, sizeof(has_identity), false))
        outcome.value.loader = KModErr::ERR_READ_CHILD_ERRCODE;
    else if (has_identity && !transfer(pipe_fds[0], &outcome.identity, sizeof(outcome.identity), false))
        outcome.value.loader = KModErr::ERR_READ_CHILD_DATA;
    else {
        outcome.has_identity = has_identity != 0;
        if (!transfer(pipe_fds[0], &loader, sizeof(loader), false))
            outcome.value.loader = KModErr::ERR_READ_CHILD_ERRCODE;
        else if (!transfer(pipe_fds[0], &outcome.value.entry, sizeof(outcome.value.entry), false))
            outcome.value.loader = KModErr::ERR_READ_CHILD_INT;
        else if (!transfer(pipe_fds[0], &skipped, sizeof(skipped), false) ||
                 !transfer(pipe_fds[0], &state, sizeof(state), false) ||
                 !transfer(pipe_fds[0], &bootstrap, sizeof(bootstrap), false))
            outcome.value.loader = KModErr::ERR_READ_CHILD_DATA;
        else {
            outcome.value.loader = static_cast<KModErr>(loader);
            outcome.value.skipped = skipped != 0;
            outcome.state = static_cast<KModErr>(state);
            outcome.bootstrap_error = static_cast<KModErr>(bootstrap);
            outcome.complete = true;
        }
    }
    close(pipe_fds[0]);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        outcome.complete = false;
        if (is_ok(outcome.value.loader)) outcome.value.loader = KModErr::ERR_READ_CHILD_ERRCODE;
    }
    if (!outcome.complete && !outcome.has_identity && is_failed(outcome.value.loader))
        outcome.bootstrap_error = outcome.state = outcome.value.loader;
    return outcome;
}
Outcome run_owned(const char* id, const module::SdkVersion& version,
                  bool reset, int fd, bool& announced, storage::WorkerScope& storage_worker) {
    bool removed = false;
    Outcome outcome = pending_removal_state(id, reset, removed);
    if (removed || is_failed(outcome.state)) return outcome;
    if (reset) {
        outcome.state = clear_states(id);
        if (is_failed(outcome.state)) { outcome.value.loader = outcome.state; return outcome; }
    }
    const std::string directory = private_directory(id);
    if (directory.empty()) {
        outcome.bootstrap_error = outcome.state = outcome.value.loader = KModErr::ERR_MODULE_ROOT_KEY;
        return outcome;
    }
    std::ifstream stream(directory + id, std::ios::binary);
    if (!stream) { outcome.value.loader = KModErr::ERR_MODULE_READ_FILE; return outcome; }
    std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(stream), {}};
    if (stream.bad()) { outcome.value.loader = KModErr::ERR_MODULE_READ_FILE; return outcome; }
    module::Descriptor desc;
    outcome.value.loader = module::parse_metadata(bytes, desc);
    if (is_failed(outcome.value.loader)) return outcome;
    if (!memchr(desc.id32, 0, sizeof(desc.id32)) || strcmp(desc.id32, id) != 0) {
        outcome.value.loader = KModErr::ERR_MODULE_PARAM; return outcome;
    }
    outcome.value = validate(desc, version);
    if (is_failed(outcome.value.loader)) {
        if (reset && !outcome.value.skipped) outcome.state = publish(id, outcome.value);
        return outcome;
    }
    std::vector<std::string> pending, finished;
    outcome.state = module_state::read(List::PendingRunOnce, pending);
    if (is_ok(outcome.state)) outcome.state = module_state::read(List::FinishedRunOnce, finished);
    if (is_failed(outcome.state)) outcome.bootstrap_error = outcome.state;
    // Persist consumption before clearing the request: a crash must not replay it.
    if (is_ok(outcome.state) && contains(pending, id)) outcome.state = module_state::add(List::FinishedRunOnce, id);
    if (is_ok(outcome.state)) outcome.state = module_state::remove(List::PendingRunOnce, id);
    if (is_failed(outcome.state)) { outcome.value.loader = outcome.state; return outcome; }
    if (contains(finished, id)) { outcome.value.skipped = true; return outcome; }
    if (!announce(fd, id, announced)) {
        outcome.value.loader = KModErr::ERR_READ_CHILD_DATA; return outcome;
    }
    try { outcome.value = execute_owned(desc); }
    catch (...) { outcome.value.loader = KModErr::ERR_MODULE_DLOPEN; }
    if (storage_worker.owner_mode()) {
        const kernel_module::CleanupReceiptScope diagnostic;
        const auto rooted = storage_worker.root();
        if (is_failed(rooted)) {
            outcome.state = outcome.bootstrap_error = rooted;
            return outcome;
        }
    }
    outcome.state = publish(id, outcome.value);
    return outcome;
}
Outcome run_module(const char* id,
                   const module::SdkVersion& version, bool reset) {
    Outcome outcome = worker(id, [&](int fd, bool& announced, storage::WorkerScope& storage_worker) {
        return run_owned(id, version, reset, fd, announced, storage_worker);
    });
    // A module may _exit or signal before publishing. Only repair that exact
    // failed generation; a later installer/runtime must retain its own state.
    if (!outcome.complete && outcome.has_identity) {
        const Outcome repaired = worker(id, [&](int, bool&, storage::WorkerScope&) -> Outcome {
            bool removed = false;
            Outcome cleanup = pending_removal_state(id, false, removed);
            if (removed || is_failed(cleanup.state)) return cleanup;
            ExecutionIdentity current;
            bool unchanged = identify(id, current) && same(current.image, outcome.identity.image);
            for (int n = 0; unchanged && n < 3; ++n)
                unchanged = same(current.states[n], outcome.identity.states[n]);
            return {{}, unchanged ? publish(id, outcome.value) : KModErr::OK};
        });
        // The short repair has its own process owner, like every other path;
        // concurrent callers never share process-owned record locks.
        remember(repaired.state, outcome.state);
        remember(repaired.bootstrap_error, outcome.bootstrap_error);
    }
    return outcome;
}
}  // namespace

Result execute(const module::Descriptor& desc) {
    if (!memchr(desc.id32, 0, sizeof(desc.id32))) return {KModErr::ERR_MODULE_PARAM, 0, false};
    return worker(desc.id32, [&](int fd, bool& announced, storage::WorkerScope&) -> Outcome {
        bool removed = false;
        Outcome outcome = pending_removal_state(desc.id32, false, removed);
        if (removed || is_failed(outcome.state)) return outcome;
        if (!announce(fd, desc.id32, announced)) return {{KModErr::ERR_READ_CHILD_DATA, 0, false}};
        return {execute_owned(desc)};
    }).value;
}

BatchResult run_boot_batch(const module::SdkVersion& version,
                           std::vector<Record>& results) {
    results.clear();
    BatchResult batch;
    auto global_failure = [&](KModErr error) {
        remember(error, batch.first_error);
        remember(error, batch.bootstrap_error);
        return batch;
    };
    storage::WorkerScope storage_worker;
    if (kernel_module::maplesu_request_owner_mode()) {
        const auto rooted = storage_worker.root();
        if (is_failed(rooted)) return global_failure(rooted);
    }
    if (system_directory().empty()) return global_failure(KModErr::ERR_MODULE_PARAM);
    std::vector<std::string> interrupted_installs;
    const KModErr pending = module_install::pending_installations(interrupted_installs);
    if (is_failed(pending)) return global_failure(pending);
    std::vector<std::string> excluded;
    for (const auto& id : interrupted_installs) {
        const Outcome restored = worker(id.c_str(), [&](int, bool&, storage::WorkerScope&) -> Outcome {
            bool found = false;
            const auto error = module_install::recover_installation(id.c_str(), found);
            return {{error, 0, false}, error};
        });
        remember(restored.state, batch.first_error);
        remember(restored.value.loader, batch.first_error);
        remember(restored.bootstrap_error, batch.bootstrap_error);
        if (is_failed(restored.state) || is_failed(restored.value.loader)) {
            excluded.push_back(id);
            fprintf(stderr, "[helper] recovery-module=%s loader=%lld state=%lld\n", id.c_str(),
                    static_cast<long long>(restored.value.loader), static_cast<long long>(restored.state));
            results.push_back({id, restored.value});
        }
    }
    std::vector<module::Record> modules;
    KModErr enumerated = module::unsafe_get_boot_modules(modules, excluded);
    if (enumerated == KModErr::ERR_MODULE_OPEN_DIR) {
        const std::string directory = system_directory();
        if (directory.empty()) return global_failure(enumerated);
        if (access((directory + "modules").c_str(), F_OK) != 0 && errno == ENOENT)
            enumerated = KModErr::OK;
    }
    if (is_failed(enumerated)) return global_failure(enumerated);
    std::vector<std::string> stale, discovered;
    for (const List state : {List::Running, List::Abnormal, List::Removed, List::PendingRemoval}) {
        std::vector<std::string> ids;
        const KModErr read = module_state::read(state, ids);
        if (is_failed(read)) return global_failure(read);
        for (const auto& id : ids) if (!contains(stale, id)) stale.push_back(id);
    }
    // The list is discovery only. Reopen and revalidate each candidate while
    // owning it, preserving the list's existing modification-time order.
    for (const auto& module : modules) {
        if (!memchr(module.desc.id32, 0, sizeof(module.desc.id32))) {
            return global_failure(KModErr::ERR_MODULE_PARAM);
        }
        const std::string id = module.desc.id32;
        discovered.push_back(id);
        const Outcome outcome = run_module(id.c_str(), version, true);
        if (!outcome.value.skipped) {
            remember(outcome.value.loader, batch.first_error);
            // Keep the raw entry result in Record; batch status is only a summary.
            if (outcome.value.entry) remember(static_cast<KModErr>(-EIO), batch.first_error);
        }
        remember(outcome.state, batch.first_error);
        remember(outcome.bootstrap_error, batch.bootstrap_error);
        fprintf(stderr, "[helper] module=%s loader=%lld main=%d skipped=%d\n", id.c_str(),
                static_cast<long long>(outcome.value.loader), outcome.value.entry, outcome.value.skipped);
        results.push_back({id, outcome.value});
    }
    for (const auto& id : stale) if (!contains(discovered, id) && !contains(excluded, id)) {
        const Outcome outcome = worker(id.c_str(), [&](int, bool&, storage::WorkerScope&) -> Outcome {
            bool removed = false;
            Outcome cleanup = pending_removal_state(id.c_str(), true, removed);
            if (removed || is_failed(cleanup.state)) return cleanup;
            // A concurrent installation may have appeared since discovery.
            const std::string directory = private_directory(id.c_str());
            if (directory.empty()) {
                cleanup.bootstrap_error = cleanup.state = cleanup.value.loader = KModErr::ERR_MODULE_ROOT_KEY;
                return cleanup;
            }
            if (access((directory + id).c_str(), F_OK) == 0) return {};
            if (errno != ENOENT) return {{KModErr::ERR_MODULE_READ_FILE, 0, false}, KModErr::ERR_MODULE_READ_FILE};
            return {{}, clear_states(id.c_str())};
        });
        remember(outcome.state, batch.first_error);
        remember(outcome.bootstrap_error, batch.bootstrap_error);
        if (is_failed(outcome.value.loader))
            fprintf(stderr, "[helper] stale-module=%s loader=%lld\n", id.c_str(), static_cast<long long>(outcome.value.loader));
    }
    return batch;
}

KModErr run_batch(const module::SdkVersion& version,
                 std::vector<Record>& results) {
    return run_boot_batch(version, results).first_error;
}

Result run_single(const char* id,
                  const module::SdkVersion& version) {
    Outcome outcome = run_module(id, version, false);
    if (is_ok(outcome.value.loader) && is_failed(outcome.state)) outcome.value.loader = outcome.state;
    return outcome.value;
}
}  // namespace maplesu::module_runtime
