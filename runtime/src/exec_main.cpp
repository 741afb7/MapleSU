#include "maplesu_parasite.h"
#include "su_protocol.h"
#include "ultra_admission.h"
#include "path_injection.h"

#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <poll.h>
#include <sched.h>
#include <string>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>


namespace {
int fail(int code, const char* reason) {
    std::fprintf(stderr, "maplesu-runtime: %s\n", reason);
    return code;
}

int admit_root() {
    const int result = maplesu_ultra_acquire_root();
    if (!result && getuid() == 0 && geteuid() == 0) return 0;
    std::fprintf(stderr, "maplesu-runtime: Ultra admission rejected code=%d\n", result);
    return 69;
}

bool parse_full_uid(const char* text, uid_t& uid) {
    if (!text || !*text) return false;
    uid_t value = 0;
    for (const char* at = text; *at; ++at) {
        if (*at < '0' || *at > '9') return false;
        const uid_t digit = static_cast<uid_t>(*at - '0');
        if (value > (std::numeric_limits<uid_t>::max() - digit) / 10) return false;
        value = value * 10 + digit;
    }
    if (value < 10000) return false;
    uid = value;
    return true;
}

// Explicit requests from the authorization owner use the current SDK. No
// credential protocol or application probing runs before current-task admission.
int wire_precheck(const char* target_cmdline) {
    const auto status = kernel_root::parasite_precheck_app_with_cb(target_cmdline,
        [](const char* path, kernel_root::AppDynlibStatus state) {
            std::printf("%d %s\n", static_cast<int>(state), path);
        });
    if (status != kernel_root::PARASITE_OK) {
        std::fprintf(stderr, "maplesu-runtime: precheck rejected code=%lld\n",
            static_cast<long long>(status));
        return 66;
    }
    return 0;
}

int wire_implant(const char* target_cmdline, const char* library, const char* su_directory) {
    const auto status = kernel_root::parasite_implant_su_env(target_cmdline, library, su_directory);
    if (status != kernel_root::PARASITE_OK) {
        std::fprintf(stderr, "maplesu-runtime: implant rejected code=%lld\n",
            static_cast<long long>(status));
        return 67;
    }
    std::printf("WIRED:%s\n", library);
    return 0;
}

int wire_implant_app(const char* target_cmdline, const char* library) {
    const auto status = kernel_root::parasite_implant_app(target_cmdline, library);
    if (status != kernel_root::PARASITE_OK) {
        std::fprintf(stderr, "maplesu-runtime: implant app rejected code=%lld\n",
            static_cast<long long>(status));
        return 68;
    }
    std::printf("IMPLANTED:%s\n", library);
    return 0;
}

int inherited_control() {
    // The JNI launcher explicitly dup2s one connected socketpair endpoint to 3.
    // No name, bind, listen or rendezvous file is registered for a session.
    constexpr int control = 3;
    ucred peer{};
    socklen_t size = sizeof(peer);
    int type = 0;
    socklen_t type_size = sizeof(type);
    sockaddr address{};
    socklen_t address_size = sizeof(address);
    if (getsockopt(control, SOL_SOCKET, SO_PEERCRED, &peer, &size) != 0 ||
        peer.uid != getuid() || getsockopt(control, SOL_SOCKET, SO_TYPE, &type, &type_size) != 0 ||
        type != SOCK_STREAM || getpeername(control, &address, &address_size) != 0 ||
        address.sa_family != AF_UNIX || fcntl(control, F_SETFD, FD_CLOEXEC) != 0) {
        close(control);
        return -1;
    }
    // Consume the parent handshake before authenticating or parsing a request.
    char accepted = 0;
    ssize_t count;
    do { count = read(control, &accepted, 1); } while (count < 0 && errno == EINTR);
    if (count != 1 || accepted != 'A') { close(control); return -1; }
    return control;
}

int supervised_exec(int control, char** command) {
    const pid_t child = fork();
    if (child < 0) return fail(71, "session fork failed");
    if (child == 0) {
        close(control);
        if (setpgid(0, 0) != 0) _exit(71);
        execv(command[0], command);
        _exit(errno == ENOENT ? 127 : 126);
    }
    // Both sides set the group to avoid a cancellation race before the child's first instruction.
    if (setpgid(child, child) != 0 && errno != EACCES && errno != ESRCH) {
        kill(child, SIGKILL);
    }
    if (send(control, "R", 1, MSG_NOSIGNAL) != 1) {
        kill(-child, SIGKILL);
        kill(child, SIGKILL);
    }
    close(STDIN_FILENO);
    close(STDOUT_FILENO);
    close(STDERR_FILENO);
    int status = 0;
    bool cancelled = false;
    while (true) {
        siginfo_t exited{};
        const int waited = waitid(P_PID, static_cast<id_t>(child), &exited, WEXITED | WNOHANG | WNOWAIT);
        if (waited == 0 && exited.si_pid == child) {
            // The unreaped leader keeps its ID reserved while we terminate remaining group members.
            kill(-child, SIGKILL);
            while (waitpid(child, &status, 0) < 0) { if (errno != EINTR) break; }
            break;
        }
        if (waited < 0) {
            if (errno == EINTR) continue;
            kill(-child, SIGKILL);
            close(control);
            return 71;
        }
        pollfd event{control, POLLIN | POLLHUP | POLLERR, 0};
        const int result = poll(&event, 1, 100);
        if (result < 0 && errno == EINTR) continue;
        if (result != 0) {
            // Any control byte, EOF, or broken owner connection cancels this command group.
            kill(-child, SIGKILL);
            cancelled = true;
        }
        if (cancelled) {
            while (waitpid(child, &status, 0) < 0) { if (errno != EINTR) break; }
            break;
        }
    }
    close(control);
    if (cancelled) return 137;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 71;
}
} // namespace

int main(int argc, char** argv) {
    // The Manager reaches these explicit operations after its fullUID grant check.
    // Authenticate before touching another application's processes or files.
    if (argc == 3 && std::strcmp(argv[1], "--wire-su-precheck") == 0) {
        const int admitted = admit_root();
        return admitted ? admitted : wire_precheck(argv[2]);
    }
    if (argc == 4 && std::strcmp(argv[1], "--implant-app") == 0) {
        const int admitted = admit_root();
        return admitted ? admitted : wire_implant_app(argv[2], argv[3]);
    }
    if ((argc == 5 || argc == 6) && std::strcmp(argv[1], "--inject-path") == 0) {
        uid_t full_uid;
        if (!parse_full_uid(argv[3], full_uid)) return fail(64, "a full application UID is required");
        const int admitted = admit_root();
        if (admitted) return admitted;
        int wait_ms = argc == 6 ? std::atoi(argv[5]) : 3000;
        if (wait_ms < 0) wait_ms = 0;
        return maplesu_inject_path(argv[2], full_uid, argv[4], wait_ms);
    }
    if (argc == 5 && std::strcmp(argv[1], "--wire-su-env") == 0) {
        const int admitted = admit_root();
        return admitted ? admitted : wire_implant(argv[2], argv[3], argv[4]);
    }
    if (argc < 4 || std::strcmp(argv[2], "--") != 0 || argv[3][0] != '/')
        return fail(64, "usage: --ultra|--ultra-global|--ultra-session|--ultra-global-session|--ultra-su|--unprivileged -- /absolute/program [args...]");
    const bool ultra_su = std::strcmp(argv[1], "--ultra-su") == 0;
    const bool ultra_session = ultra_su || std::strcmp(argv[1], "--ultra-session") == 0 ||
        std::strcmp(argv[1], "--ultra-global-session") == 0;
    const bool ultra_global = std::strcmp(argv[1], "--ultra-global") == 0 ||
        std::strcmp(argv[1], "--ultra-global-session") == 0;
    const bool ultra = ultra_session || ultra_global || std::strcmp(argv[1], "--ultra") == 0;
    const bool root_su = std::strcmp(argv[1], "--root-su") == 0;
    const bool root_session = std::strcmp(argv[1], "--root-session") == 0;
    const bool root_global_session = std::strcmp(argv[1], "--root-global-session") == 0;
    if (!ultra && !root_su && !root_session && !root_global_session && std::strcmp(argv[1], "--unprivileged") != 0)
        return fail(64, "unknown execution mode");
    if (root_su || root_session || root_global_session) {
        if (getuid() != 0 || geteuid() != 0) return fail(70, "root session owner required");
        const int root_control = inherited_control();
        if (root_control < 0) return fail(65, "invalid root session control connection");
        if (root_global_session) {
            // Only this separately spawned executor joins init's namespace.
            const int mount_namespace = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
            if (mount_namespace < 0) return fail(71, "global mount namespace open failed");
            const int joined = setns(mount_namespace, CLONE_NEWNS);
            close(mount_namespace);
            if (joined != 0) return fail(71, "global mount namespace join failed");
        }
        // A restricted su request runs behind the authorization owner's commit gate:
        // prepare, report PREPARED, wait for G, and only then execute. The owner's
        // single commit byte is the linearization point of the admission decision.
        return root_su ? maplesu::su_session(root_control, /*gated=*/true)
                       : supervised_exec(root_control, argv + 3);
    }
    if (ultra) {
        const int control = ultra_session ? inherited_control() : -1;
        if (ultra_session && control < 0) return fail(65, "invalid session control connection");
        const int admitted = admit_root();
        if (admitted) {
            if (control >= 0) close(control);
            return admitted;
        }
        if (ultra_global) {
            const int mount_namespace = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
            if (mount_namespace < 0) return fail(71, "global mount namespace open failed");
            const int joined = setns(mount_namespace, CLONE_NEWNS);
            close(mount_namespace);
            if (joined != 0) return fail(71, "global mount namespace join failed");
        }
        if (signal(SIGPIPE, SIG_DFL) == SIG_ERR) return fail(71, "signal setup failed");
        if (ultra_su) return maplesu::su_session(control, /*gated=*/false);
        if (ultra_session) return supervised_exec(control, argv + 3);
        execv(argv[3], argv + 3);
        return fail(errno == ENOENT ? 127 : 126, "command exec failed");
    }
    if (getuid() == 0 || geteuid() == 0 || getuid() != geteuid() || getgid() != getegid())
        return fail(70, "requires an ordinary, unchanged caller identity");
    // execv preserves argv, cwd, environment, stdio and actual command exit status.
    // No popen, su fallback, shell interpolation, or success response before exec.
    execv(argv[3], argv + 3);
    const int error = errno;
    return fail(error == ENOENT ? 127 : 126, "command exec failed");
}
