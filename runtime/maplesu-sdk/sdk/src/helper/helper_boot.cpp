#include "private/bootstrap_runtime.h"
#include "maplesu_storage_worker_private.h"
#include "maplesu_module_environment_private.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <optional>
#include <string>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

extern char** environ;
namespace {
helper_context g_context;
constexpr char builtin_storage_id[] = "GEfQdKmhZSkuWJI2rs21JO39Xmp8XcZg";

KModErr acquire_worker_root(helper_context& context, maplesu::storage::WorkerScope& worker) {
    RETURN_IF_ERROR(worker.root());
    std::string directory;
    RETURN_IF_ERROR(maplesu::storage::current_kernel_directory(directory));
    return directory == std::string(context.env_dir) + "/" ? KModErr::OK : KModErr::ERR_MODULE_ROOT_KEY;
}

class LogFile {
public:
    explicit LogFile(const char* self) {
        char path[HELPER_PATH_MAX + 5];
        if (snprintf(path, sizeof(path), "%s.log", self) <= 0) return;
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd < 0) return;
        struct stat st{};
        if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid || st.st_nlink != 1) { close(fd); return; }
        fflush(nullptr);
        out_ = dup(STDOUT_FILENO); err_ = dup(STDERR_FILENO);
        if (out_ >= 0 && err_ >= 0) { dup2(fd, STDOUT_FILENO); dup2(fd, STDERR_FILENO); }
        close(fd);
    }
    void restore() {
        fflush(nullptr);
        if (out_ >= 0) { dup2(out_, STDOUT_FILENO); close(out_); out_ = -1; }
        if (err_ >= 0) { dup2(err_, STDERR_FILENO); close(err_); err_ = -1; }
    }
    ~LogFile() { restore(); }
private:
    int out_ = -1, err_ = -1;
};

int read_self_path(helper_context& context, char* const argv[]) {
    helper_u32 length = 0;
    int result = context.ops.read_self_path(context.self_path, sizeof(context.self_path), &length);
    if (result || !length) {
        length = helper_strlen(argv[0]);
        if (!length || length >= sizeof(context.self_path)) return HELPER_E_INVAL;
        helper_memcopy(context.self_path, argv[0], length + 1);
    }
    return HELPER_OK;
}
helper_i64 locate(helper_context& context) {
    char expected[HELPER_PATH_MAX];
    helper_memcopy(context.module_id, HELPER_MODULE_ID, sizeof(HELPER_MODULE_ID));
    helper_memzero(context.env_dir, sizeof(context.env_dir));
    maplesu_installation_state_v3 installation{};
    const KModErr read = kernel_module::maplesu_request_helper_vendor(MAPLESU_INSTALLATION_READ, 0, installation);
    if (is_failed(read)) return static_cast<helper_i64>(read);
    helper_memcopy(context.env_dir, "/data/", 6);
    static_assert(sizeof(installation.name) == 16);
    helper_memcopy(context.env_dir + 6, installation.name, sizeof(installation.name));
    context.env_dir[6 + sizeof(installation.name)] = 0;
    if (snprintf(expected, sizeof(expected), "%s/init", context.env_dir) <= 0 ||
        !helper_streq(expected, context.self_path)) return HELPER_E_ACCES;
    const KModErr selected = kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::EarlyHelper);
    if (is_failed(selected)) return static_cast<helper_i64>(selected);
    return HELPER_OK;
}
int take_lock(helper_context& context, bool wait) {
    char path[HELPER_PATH_MAX];
    int result = helper_state_path(&context, HELPER_SUFFIX_LOCK, path, sizeof(path));
    if (result) return result;
    context.lock_fd = context.ops.open_path(path, HELPER_OPEN_READ | HELPER_OPEN_WRITE |
        HELPER_OPEN_CREATE | HELPER_OPEN_NOFOLLOW, 0600);
    if (context.lock_fd < 0) return context.lock_fd;
    return context.ops.flock_dir(context.lock_fd, HELPER_LOCK_EX | (wait ? 0 : HELPER_LOCK_NB));
}
int unlock(helper_context& context) {
    if (context.lock_fd >= 0) {
        const int unlocked = context.ops.flock_dir(context.lock_fd, HELPER_LOCK_UN);
        const int closed = context.ops.close(context.lock_fd);
        context.lock_fd = -1;
        return unlocked ? unlocked : closed;
    }
    return HELPER_OK;
}

constexpr const char* capability_suffix = ".boot_session.capability";
constexpr helper_u32 capability_size = 88;

void store_u64(char* bytes, helper_u64 value) {
    for (helper_u32 i = 0; i < 8; ++i) bytes[i] = static_cast<char>(value >> (i * 8));
}
helper_u64 load_u64(const char* bytes) {
    helper_u64 value = 0;
    for (helper_u32 i = 0; i < 8; ++i)
        value |= static_cast<helper_u64>(static_cast<unsigned char>(bytes[i])) << (i * 8);
    return value;
}
void work_identity(const struct stat& info, helper_u64* identity) {
    identity[0] = info.st_dev; identity[1] = info.st_ino;
    identity[2] = info.st_ctim.tv_sec; identity[3] = info.st_ctim.tv_nsec;
}
int read_work_identity(helper_context& context, helper_u64* identity) {
    char path[HELPER_PATH_MAX], receipt[HELPER_RECEIPT_LEN];
    int result = helper_state_path(&context, HELPER_SUFFIX_WORK, path, sizeof(path));
    if (result) return result;
    const int fd = context.ops.open_path(path, HELPER_OPEN_READ | HELPER_OPEN_NOFOLLOW |
        HELPER_OPEN_NONBLOCK, 0);
    if (fd < 0) return fd;
    struct stat before{}, after{};
    if (fstat(fd, &before)) result = -errno;
    if (!result && (!S_ISREG(before.st_mode) || before.st_uid || before.st_nlink != 1 ||
        before.st_size != HELPER_RECEIPT_LEN)) result = HELPER_E_INVAL;
    if (!result) {
        const long long count = context.ops.read(fd, receipt, sizeof(receipt));
        if (count < 0) result = static_cast<int>(count);
        else if (count != HELPER_RECEIPT_LEN || receipt[HELPER_BOOT_ID_LEN] != '\n' ||
            !helper_memeq(receipt, context.boot_id, HELPER_BOOT_ID_LEN)) result = HELPER_E_INVAL;
    }
    if (!result && fstat(fd, &after)) result = -errno;
    helper_u64 check[4];
    if (!result) {
        work_identity(before, identity); work_identity(after, check);
        if (!helper_memeq(identity, check, sizeof(check)) || after.st_size != before.st_size ||
            after.st_uid || after.st_nlink != 1) result = HELPER_E_INVAL;
    }
    if (context.ops.close(fd) && !result) result = HELPER_E_IO;
    return result;
}
void record_capability(helper_context& context) {
    char bytes[capability_size]{};
    helper_u64 identity[4];
    int result = read_work_identity(context, identity);
    if (!result) {
        helper_memcopy(bytes, "MSCAP01\n", 8);
        helper_memcopy(bytes + 8, context.boot_id, HELPER_BOOT_ID_LEN);
        bytes[44] = context.capability_stage;
        for (helper_u32 i = 0; i < 4; ++i) store_u64(bytes + 48 + i * 8, identity[i]);
        store_u64(bytes + 80, static_cast<helper_u64>(context.capability_error));
        result = helper_session_publish(&context, capability_suffix, bytes, sizeof(bytes));
    }
    context.capability_record_error = result;
}
void read_capability(helper_context& context) {
    helper_u64 identity[4], current[4];
    int result = read_work_identity(context, identity);
    char path[HELPER_PATH_MAX], bytes[capability_size];
    if (!result) result = helper_state_path(&context, capability_suffix, path, sizeof(path));
    if (!result) {
        const int fd = context.ops.open_path(path, HELPER_OPEN_READ | HELPER_OPEN_NOFOLLOW |
            HELPER_OPEN_NONBLOCK, 0);
        if (fd < 0) result = fd;
        else {
            helper_stat_info info;
            result = context.ops.fstat(fd, &info);
            if (!result && (!info.is_reg || info.uid || info.nlink != 1 || info.size != sizeof(bytes)))
                result = HELPER_E_INVAL;
            if (!result) {
                const long long count = context.ops.read(fd, bytes, sizeof(bytes));
                result = count < 0 ? static_cast<int>(count) :
                    (count == sizeof(bytes) ? HELPER_OK : HELPER_E_IO);
            }
            if (context.ops.close(fd) && !result) result = HELPER_E_IO;
        }
    }
    if (!result && (!helper_memeq(bytes, "MSCAP01\n", 8) ||
        !helper_memeq(bytes + 8, context.boot_id, HELPER_BOOT_ID_LEN) || bytes[45] || bytes[46] || bytes[47]))
        result = HELPER_E_INVAL;
    if (!result) for (helper_u32 i = 0; i < 4; ++i)
        if (load_u64(bytes + 48 + i * 8) != identity[i]) result = HELPER_E_INVAL;
    if (!result) result = read_work_identity(context, current);
    if (!result && !helper_memeq(identity, current, sizeof(identity))) result = HELPER_E_INVAL;
    if (!result) {
        const helper_u64 value = load_u64(bytes + 80);
        const helper_i64 error = value <= 0x7fffffffffffffffULL ? static_cast<helper_i64>(value) :
            -1 - static_cast<helper_i64>(~value);
        const char stage = bytes[44];
        if ((stage != 'C' && stage != 'P' && stage != 'U') || (stage == 'C' ? error != 0 : error == 0))
            result = HELPER_E_INVAL;
        else { context.capability_stage = stage; context.capability_error = error; }
    }
    context.capability_record_error = result;
}
int capability_result(helper_context& context, int result) {
    char stage[] = {context.capability_stage, 0};
    helper_log_pair(&context, "module-capability stage=", stage);
    helper_log_error(&context, "module-capability rc=", context.capability_error);
    if (context.capability_record_error)
        helper_log_error(&context, "module-capability record rc=", context.capability_record_error);
    return result ? result : (context.capability_record_error || context.capability_stage != 'C' ||
        context.capability_error ? HELPER_EXIT_ENVIRONMENT : HELPER_EXIT_OK);
}
int dispatch_boot_event(helper_context& context, const char* event, char* const envp[]);

int boot_child(helper_context& context, std::optional<maplesu::storage::WorkerScope>& worker,
               const char* before_modules = nullptr, char* const envp[] = nullptr) {
    helper_u32 length = 0;
    int result = context.ops.read_boot_id(context.boot_id, sizeof(context.boot_id), &length);
    if (result || !helper_boot_id_valid(context.boot_id, length)) return HELPER_EXIT_ENVIRONMENT;
    helper_log_pair(&context, "boot-session attempt: ", context.boot_id);
    int action = HELPER_SESSION_NORMAL;
    result = helper_session_begin(&context, &action);
    if (result == HELPER_E_AGAIN) {
        helper_log(&context, "bootstrap incomplete in current boot; explicit recovery required");
        return HELPER_EXIT_ENVIRONMENT;
    }
    if (result) {
        // Reading/admitting a session has not executed initialization. Preserve
        // malformed/unreadable state for repair instead of converting it into a
        // new bootstrap crash and overwriting its original marker.
        helper_log_error(&context, "boot-session rc=", result);
        return HELPER_EXIT_ENVIRONMENT;
    }
    if (action == HELPER_SESSION_COMPLETED) {
        read_capability(context);
        return before_modules ? dispatch_boot_event(context, before_modules, envp) : HELPER_EXIT_OK;
    }
    if (action == HELPER_SESSION_PROTECTED) {
        helper_log(&context, "boot-session retained protection; initialization not attempted");
        return HELPER_EXIT_PROTECTED;
    }
    int event_result = HELPER_EXIT_OK;
    try {
        if (before_modules) {
            const KModErr core = maplesu::bootstrap::initialize_core(context, worker);
            if (is_failed(core)) return helper_session_fail(&context, static_cast<int64_t>(core));
            // The actual post-fs-data consumer finishes common scripts, module
            // policy, properties and mounts before a native Zygisk host loads.
            event_result = dispatch_boot_event(context, before_modules, envp);
            if (event_result) helper_log_error(&context, "boot-event rc=", event_result);
            const KModErr modules = maplesu::bootstrap::initialize_modules(context);
            if (is_failed(modules)) return helper_session_fail(&context, static_cast<int64_t>(modules));
        } else {
            const KModErr initialized = maplesu::bootstrap::initialize(context, worker);
            if (is_failed(initialized)) return helper_session_fail(&context, static_cast<int64_t>(initialized));
        }
    } catch (...) { return helper_session_fail(&context, HELPER_E_UNKNOWN); }
    if (worker) {
        try {
            const KModErr rooted = acquire_worker_root(context, *worker);
            if (is_failed(rooted)) {
                helper_log_error(&context, "boot-worker-commit-root rc=", static_cast<int64_t>(rooted));
                return event_result ? event_result : HELPER_EXIT_ENVIRONMENT;
            }
        } catch (...) {
            helper_log_error(&context, "boot-worker-commit-root rc=", HELPER_E_UNKNOWN);
            return event_result ? event_result : HELPER_EXIT_ENVIRONMENT;
        }
    }
    result = helper_session_finish(&context);
    if (result) return helper_session_fail(&context, result);
    record_capability(context);
    helper_log_pair(&context, "boot session:", context.boot_id);
    return event_result;
}

int exec_boot_event_child(helper_context& context, const char* dispatcher,
                          const char* event, char* const envp[]) {
    if (context.lock_fd >= 0) {
        const int closed = context.ops.close(context.lock_fd);
        context.lock_fd = -1;
        if (closed) {
            helper_log_error(&context, "boot-event-child-lock-close rc=", closed);
            return HELPER_EXIT_ENVIRONMENT;
        }
    }
    maplesu::storage::WorkerScope worker;
    maplesu::module_environment::Environment environment;
    if (worker.owner_mode()) {
        const KModErr rooted = acquire_worker_root(context, worker);
        if (is_failed(rooted)) {
            helper_log_error(&context, "boot-event-child-root rc=", static_cast<int64_t>(rooted));
            return HELPER_EXIT_ENVIRONMENT;
        }
    }
    const auto version = maplesu::bootstrap::sdk_version();
    const KModErr captured = maplesu::module_environment::capture(
        version.major, version.minor, version.patch, envp ? envp : environ, environment);
    if (is_failed(captured)) {
        helper_log_error(&context, "boot-event-environment rc=", static_cast<int64_t>(captured));
        return HELPER_EXIT_ENVIRONMENT;
    }
    if (worker.owner_mode()) {
        // Exec keeps Root credentials after this worker's own session is closed.
        const KModErr closed = worker.close_checked();
        if (is_failed(closed)) {
            helper_log_error(&context, "boot-event-child-close rc=", static_cast<int64_t>(closed));
            return HELPER_EXIT_ENVIRONMENT;
        }
    }
    char* args[] = {const_cast<char*>("/system/bin/sh"), const_cast<char*>(dispatcher),
                    const_cast<char*>(event), nullptr};
    const int result = context.ops.execve(args[0], args, environment.data());
    helper_log_error(&context, "boot-event-exec rc=", result);
    return HELPER_EXIT_ENVIRONMENT;
}

int dispatch_boot_event(helper_context& context, const char* event, char* const envp[]) {
    maplesu::storage::WorkerScope worker;
    const KModErr rooted = acquire_worker_root(context, worker);
    if (is_failed(rooted)) {
        helper_log_error(&context, "boot-event-root rc=", static_cast<int64_t>(rooted));
        return HELPER_EXIT_ENVIRONMENT;
    }
    // The executable scripts are persisted while installing/upgrading module
    // tools. Event execution reads no APK or Manager-private files.
    for (const char* parent : {"/data/adb", "/data/adb/maplesu", "/data/adb/maplesu/bin"}) {
        struct stat st{};
        if (lstat(parent, &st) || !S_ISDIR(st.st_mode) || st.st_uid || (st.st_mode & 0022)) {
            helper_log_pair(&context, "boot-event invalid parent: ", parent);
            return HELPER_EXIT_ENVIRONMENT;
        }
    }
    const char* dispatcher = "/data/adb/maplesu/bin/module-boot.sh";
    struct stat st{};
    if (lstat(dispatcher, &st) || !S_ISREG(st.st_mode) || st.st_uid || st.st_nlink != 1 ||
        (st.st_mode & 0022)) {
        helper_log(&context, "boot-event dispatcher is absent or invalid");
        return HELPER_EXIT_ENVIRONMENT;
    }
    helper_log_pair(&context, "boot-event: ", event);
    // Keep the initialized process alive to load native modules afterwards.
    // Detached scripts must inherit neither the helper lock nor its descriptor.
    fflush(nullptr);
    const pid_t child = fork();
    if (child == 0) {
        int result = HELPER_EXIT_ENVIRONMENT;
        try { result = exec_boot_event_child(context, dispatcher, event, envp); }
        catch (...) { helper_log_error(&context, "boot-event-child rc=", HELPER_E_UNKNOWN); }
        _exit(result);
    }
    int status = 0;
    pid_t waited = -1;
    if (child > 0) do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (child < 0 || waited != child || !WIFEXITED(status)) {
        const int error = child < 0 || waited < 0 ? errno : HELPER_E_UNKNOWN;
        helper_log_error(&context, "boot-event-worker rc=", error);
        return HELPER_EXIT_ENVIRONMENT;
    }
    return WEXITSTATUS(status);
}

int boot_event_child(helper_context& context, std::optional<maplesu::storage::WorkerScope>& worker,
                     const char* event, char* const envp[]) {
    if (helper_streq(event, "post-fs-data")) return boot_child(context, worker, event, envp);
    const int initialized = boot_child(context, worker);
    return initialized ? initialized : dispatch_boot_event(context, event, envp);
}
int passthrough(helper_context& context, char* const argv[], char* const envp[]) {
    // Preserve the original init-only interception handoff and exact argv/env.
    if (prctl(PR_SET_NAME, "zygote64", 0, 0, 0)) return errno;
    const int executed = context.ops.execve(argv[0], argv, envp ? envp : environ);
    return executed < 0 ? -executed : executed;
}
}

int helper_run_boot(const helper_ops* ops, int argc, char* const argv[], char* const envp[]) {
    if (!ops || argc < 1 || !argv || !argv[0]) return HELPER_EXIT_USAGE;
    helper_memzero(&g_context, sizeof(g_context));
    g_context.ops = *ops;
    g_context.lock_fd = -1;
    g_context.capability_stage = 'U';
    g_context.capability_error = HELPER_E_UNKNOWN;
    if (read_self_path(g_context, argv)) return HELPER_EXIT_ENVIRONMENT;
    // Exec interception preserves the system target's absolute argv[0]. The
    // tombstone helper uses a random basename, which remains a helper command.
    const bool original_target = argv[0][0] == '/' && !helper_streq(argv[0], g_context.self_path);
    if (!original_target && argc == 2 && helper_streq(argv[1], "--suicide")) {
        g_context.ops.abort_process();
        return HELPER_EXIT_PROTECTED;
    }
    const bool single = !original_target && argc == 3 && helper_streq(argv[1], "--load-single-module");
    const bool event = !original_target && argc == 3 && helper_streq(argv[1], "--boot-event") &&
        (helper_streq(argv[2], "post-fs-data") || helper_streq(argv[2], "post-mount") ||
         helper_streq(argv[2], "service") || helper_streq(argv[2], "boot-completed"));
    if (!original_target && argc > 1 && argv[1][0] == '-' && !single && !event) return HELPER_EXIT_USAGE;
    const helper_i64 located = locate(g_context);
    if (located) {
        helper_log_error(&g_context, "bootstrap-location rc=", located);
        return original_target ? passthrough(g_context, argv, envp) : HELPER_EXIT_ENVIRONMENT;
    }
    LogFile log(g_context.self_path);
    const auto version = maplesu::bootstrap::sdk_version();
    fprintf(stderr, "[helper] MapleSU: %u.%u.%u source bootstrap\n", version.major, version.minor, version.patch);
    if (single) return maplesu::bootstrap::load_single(g_context, argv[2]);
    fflush(nullptr);
    const pid_t child = fork();
    if (child == 0) {
        int base_result = HELPER_EXIT_ENVIRONMENT;
        std::optional<maplesu::storage::WorkerScope> worker;
        try {
            KModErr rooted = KModErr::OK;
            if (kernel_module::maplesu_request_owner_mode()) {
                worker.emplace(builtin_storage_id);
                rooted = acquire_worker_root(g_context, *worker);
            }
            if (is_failed(rooted))
                helper_log_error(&g_context, "boot-worker-root rc=", static_cast<int64_t>(rooted));
            else {
                const int locked = take_lock(g_context, event);
                if (locked) helper_log_error(&g_context, "bootstrap-lock rc=", locked);
                else base_result = event ? boot_event_child(g_context, worker, argv[2], envp) :
                    boot_child(g_context, worker);
            }
        } catch (...) { helper_log_error(&g_context, "boot-worker rc=", HELPER_E_UNKNOWN); }
        int result = capability_result(g_context, base_result);
        const int unlocked = unlock(g_context);
        if (unlocked) {
            helper_log_error(&g_context, "boot-worker-unlock rc=", unlocked);
            if (result == HELPER_EXIT_OK) result = HELPER_EXIT_ENVIRONMENT;
        }
        if (worker) {
            const KModErr closed = worker->close_checked();
            if (is_failed(closed)) {
                helper_log_error(&g_context, "boot-worker-close rc=", static_cast<int64_t>(closed));
                if (result == HELPER_EXIT_OK) result = HELPER_EXIT_ENVIRONMENT;
            }
        }
        fflush(nullptr);
        _exit(result);
    }
    int status = 0;
    pid_t waited = -1;
    if (child > 0) do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (child < 0 || waited != child || !WIFEXITED(status))
        fprintf(stderr, "[helper] boot worker pid=%d waited=%d status=%d signal=%d errno=%d\n",
                child, waited, status, WIFSIGNALED(status) ? WTERMSIG(status) : 0, errno);
    log.restore();
    if (child < 0 || waited != child || !WIFEXITED(status))
        return original_target ? passthrough(g_context, argv, envp) : HELPER_EXIT_ENVIRONMENT;
    const int result = WEXITSTATUS(status);
    if ((!original_target && argc < 2) || event) return result;
    // Kernel exec interception supplies the target as argv[0]. Preserve that
    // vector and environment after releasing all helper resources.
    if (helper_streq(argv[0], g_context.self_path)) return HELPER_EXIT_USAGE;
    return passthrough(g_context, argv, envp);
}
