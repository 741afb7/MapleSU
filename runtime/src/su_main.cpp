#include "su_protocol.h"
#include <algorithm>
#include <climits>
#include <csignal>
#include <cstdio>
#include <dirent.h>
#include <fstream>
#include <cstdlib>
#include <cstddef>
#include <cstring>
#include <grp.h>
#include <sched.h>
#include <poll.h>
#include <pwd.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <termios.h>

extern char** environ;
extern "C" pid_t setsid(void) { return 0; }
namespace {
volatile sig_atomic_t pending_signals = 0;
void received_signal(int value) {
    if (value == SIGWINCH) pending_signals |= 1;
    else if (value == SIGINT) pending_signals |= 2;
    else if (value == SIGTERM) pending_signals |= 4;
    else if (value == SIGHUP) pending_signals |= 8;
    else if (value == SIGQUIT) pending_signals |= 16;
    else if (value == SIGTSTP) pending_signals |= 32;
    else if (value == SIGCONT) pending_signals |= 64;
}
struct Terminal {
    int fd = -1;
    termios saved{};
    bool changed = false;
    ~Terminal() { if (changed) tcsetattr(fd, TCSANOW, &saved); }
    bool raw(int descriptor) {
        fd = descriptor;
        if (tcgetattr(fd, &saved) != 0) return false;
        termios mode = saved;
        cfmakeraw(&mode);
        changed = tcsetattr(fd, TCSANOW, &mode) == 0;
        return changed;
    }
};
int fail(const char* reason, int code = 69) { fprintf(stderr, "MapleSU: %s\n", reason); return code; }
bool number(const std::string& text, uint32_t& value) {
    if (text.empty() || text.size() > 10) return false;
    uint64_t result = 0;
    for (char character : text) {
        if (character < '0' || character > '9') return false;
        result = result * 10 + character - '0';
        if (result > INT_MAX) return false;
    }
    value = static_cast<uint32_t>(result); return true;
}
// The client carries no configuration and reads no metadata file. It is published
// as a symbolic link to <apkd>/lib/<abi>/libmaplesu_su.so, so the kernel resolves
// /proc/self/exe to that image and the two paths the bootstrap needs are derived
// from it:
//
//   <apkd>/lib/<abi>/libmaplesu_su.so   (this process)
//   <apkd>/lib/<abi>/libmaplesu_bootstrap.so
//   <apkd>/base.apk                     (CLASSPATH for app_process)
//
// This is why /dev/maplesu no longer holds manager.uid, manager.apk or
// manager.lib: none of the three values has to exist in a world-readable file, and
// the manager's UID is not needed either. The Binder caller identity is the real
// UID of the process that ran this client, and AMS scopes peekService to the
// caller's own Android user, so a cross-user caller can never reach the endpoint.
bool derive_layout(std::string& apk, std::string& library) {
    char resolved[PATH_MAX];
    if (!realpath("/proc/self/exe", resolved)) return false;
    const std::string image = resolved;
    const size_t name = image.rfind('/');
    if (name == std::string::npos || image.compare(name + 1, std::string::npos, "libmaplesu_su.so") != 0)
        return false;
    const std::string abi_directory = image.substr(0, name);          // <apkd>/lib/<abi>
    const size_t lib = abi_directory.rfind('/');
    if (lib == std::string::npos) return false;
    const std::string library_directory = abi_directory.substr(0, lib); // <apkd>/lib
    const size_t package = library_directory.rfind('/');
    if (package == std::string::npos) return false;
    const std::string application_directory = library_directory.substr(0, package); // <apkd>
    // Only accept the installed application directory of this product, so a copy
    // placed anywhere else cannot make this client bootstrap something else.
    const std::string segment = "/" + std::string(maplesu::MANAGER_PACKAGE) + "-";
    if (application_directory.find(segment) == std::string::npos) return false;
    const std::string candidate_apk = application_directory + "/base.apk";
    const std::string candidate_library = abi_directory + "/libmaplesu_bootstrap.so";
    struct stat information{};
    if (stat(candidate_apk.c_str(), &information) != 0 || !S_ISREG(information.st_mode) ||
        information.st_size <= 0 || access(candidate_apk.c_str(), R_OK) != 0)
        return false;
    if (stat(candidate_library.c_str(), &information) != 0 || !S_ISREG(information.st_mode) ||
        information.st_size <= 0 || access(candidate_library.c_str(), R_OK) != 0)
        return false;
    apk = candidate_apk;
    library = candidate_library;
    return true;
}
struct Bootstrap {
    int control = -1;
    pid_t child = -1;
    ~Bootstrap() {
        if (control >= 0) { shutdown(control, SHUT_RDWR); close(control); }
        if (child > 0) {
            // Normal completion lets the Binder owner close first; abnormal transport loss
            // ends the same-UID helper and lets Binder death release the remote session.
            for (int attempt = 0; attempt < 20; ++attempt) {
                const pid_t result = waitpid(child, nullptr, WNOHANG);
                if (result == child || (result < 0 && errno == ECHILD)) return;
                usleep(10000);
            }
            kill(child, SIGKILL);
            while (waitpid(child, nullptr, 0) < 0 && errno == EINTR) { }
        }
    }
    bool start(uint32_t caller, const std::string& apk, const std::string& library, uint32_t terminals) {
        int pair[2];
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) != 0) return false;
        child = fork();
        if (child < 0) { close(pair[0]); close(pair[1]); return false; }
        if (child == 0) {
            close(pair[0]);
            const int null = open("/dev/null", O_RDWR | O_CLOEXEC);
            if (null < 0) _exit(69);
            const int descriptors[] = {pair[1], STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO};
            std::array<std::string, 4> inherited;
            for (size_t i = 0; i < inherited.size(); ++i) {
                // App-private devpts cannot cross UID boundaries through Binder.
                // The native caller relays terminal I/O through the root PTY instead.
                const int source = i > 0 && (terminals & (1U << (i - 1))) ? null : descriptors[i];
                const int duplicate = fcntl(source, F_DUPFD, 3);
                if (duplicate < 0) _exit(69);
                inherited[i] = std::to_string(duplicate);
            }
            close(pair[1]);
            for (int descriptor = 0; descriptor < 3; ++descriptor) if (dup2(null, descriptor) < 0) _exit(69);
            if (null > 2) close(null);
            if (setenv("CLASSPATH", apk.c_str(), 1) != 0) _exit(69);
            const std::string uid = std::to_string(caller);
            // No --nice-name: the helper must not publish a product-derived process
            // name. It runs as the calling application's own UID and Android user.
            execl("/system/bin/app_process", "app_process", "/system/bin",
                maplesu::BOOTSTRAP_CLASS, inherited[0].c_str(), inherited[1].c_str(),
                inherited[2].c_str(), inherited[3].c_str(), uid.c_str(), library.c_str(), static_cast<char*>(nullptr));
            _exit(69);
        }
        close(pair[1]);
        control = pair[0];
        return true;
    }
};
uint32_t frame_size(const maplesu::SuRequest& request) {
    size_t size = 48 + request.executable.size() + request.directory.size();
    for (const auto& value : request.arguments) size += 4 + value.size();
    for (const auto& value : request.environment) size += 4 + value.size();
    return static_cast<uint32_t>(size);
}
std::string resolve(const std::string& value) {
    if (!value.empty() && value[0] == '/') return value;
    if (value.find('/') != std::string::npos) {
        char path[PATH_MAX];
        return realpath(value.c_str(), path) ? path : "";
    }
    const char* environment = getenv("PATH");
    std::string paths = environment ? environment : "/system/bin:/system/xbin";
    for (size_t start = 0; start <= paths.size();) {
        const size_t end = paths.find(':', start);
        const std::string directory = paths.substr(start, end == std::string::npos ? end : end - start);
        const std::string candidate = (directory.empty() ? "." : directory) + "/" + value;
        if (access(candidate.c_str(), X_OK) == 0) {
            char absolute[PATH_MAX];
            if (realpath(candidate.c_str(), absolute)) return absolute;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return "";
}
void window_size(int descriptor, maplesu::SuRequest& request) {
    winsize value{};
    if (descriptor >= 0 && ioctl(descriptor, TIOCGWINSZ, &value) == 0)
        request.window = {value.ws_row, value.ws_col, value.ws_xpixel, value.ws_ypixel};
}
int relay(int socket, int master, const maplesu::SuRequest& request) {
    Terminal terminal;
    int output = request.terminals & 2 ? STDOUT_FILENO : STDERR_FILENO;
    int private_output = -1;
    if (master >= 0) {
        if ((request.terminals & 1) && !terminal.raw(STDIN_FILENO)) return fail("terminal setup failed", 71);
        if (!(request.terminals & 6)) {
            private_output = open("/dev/tty", O_WRONLY | O_CLOEXEC);
            if (private_output < 0) return fail("terminal output unavailable", 71);
            output = private_output;
        }
        const int flags = fcntl(master, F_GETFL);
        if (flags < 0 || fcntl(master, F_SETFL, flags | O_NONBLOCK) != 0) return fail("PTY setup failed", 71);
    }
    for (int signal : {SIGWINCH, SIGINT, SIGTERM, SIGHUP, SIGQUIT, SIGTSTP, SIGCONT}) {
        struct sigaction action{};
        action.sa_handler = received_signal;
        sigemptyset(&action.sa_mask);
        if (sigaction(signal, &action, nullptr) != 0) return fail("signal setup failed", 71);
    }
    std::string input, output_buffer;
    bool input_open = request.terminals & 1;
    bool master_open = master >= 0;
    int result = 69;
    bool exited = false;
    while (true) {
        sigset_t blocked, original;
        sigemptyset(&blocked);
        for (int signal : {SIGWINCH, SIGINT, SIGTERM, SIGHUP, SIGQUIT, SIGTSTP, SIGCONT}) sigaddset(&blocked, signal);
        sigprocmask(SIG_BLOCK, &blocked, &original);
        const sig_atomic_t pending = pending_signals; pending_signals = 0;
        sigprocmask(SIG_SETMASK, &original, nullptr);
        if ((pending & 1) && master >= 0) {
            auto current = request;
            window_size(STDIN_FILENO, current);
            if (!maplesu::write_all(socket, "W", 1)) break;
            for (auto value : current.window) if (!maplesu::write_u32(socket, value)) return fail("resize failed");
        }
        const int signals[] = {SIGINT, SIGTERM, SIGHUP, SIGQUIT, SIGTSTP, SIGCONT};
        for (size_t i = 0; i < std::size(signals); ++i) {
            if ((pending & (2 << i)) && (!maplesu::write_all(socket, "S", 1) || !maplesu::write_u32(socket, signals[i])))
                return fail("signal forwarding failed");
        }
        pollfd events[] = {
            {socket, static_cast<short>(exited ? 0 : POLLIN), 0},
            {master_open ? master : -1, static_cast<short>((output_buffer.size() < 65536 ? POLLIN : 0) | (!input.empty() ? POLLOUT : 0)), 0},
            {input_open && input.size() < 65536 ? STDIN_FILENO : -1, POLLIN, 0},
            {!output_buffer.empty() ? output : -1, POLLOUT, 0},
        };
        const int polled = poll(events, std::size(events), 100);
        if (polled < 0) { if (errno == EINTR) continue; break; }
        if (!exited && events[0].revents) {
            char message;
            if (!maplesu::read_all(socket, &message, 1)) break;
            uint32_t code;
            if (message != 'E' || !maplesu::read_u32(socket, code) || code > 255) break;
            result = static_cast<int>(code); exited = true; input_open = false;
        }
        if (master_open && (events[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            char bytes[4096]; const ssize_t count = read(master, bytes, sizeof(bytes));
            if (count > 0) output_buffer.append(bytes, count);
            else if (count == 0 || (count < 0 && errno == EIO)) master_open = false;
            else if (errno != EAGAIN && errno != EINTR) break;
        }
        if (events[2].revents & (POLLIN | POLLHUP)) {
            char bytes[4096]; const ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
            if (count > 0) input.append(bytes, count);
            else if (count == 0) { input_open = false; input.push_back(4); }
            else if (errno != EINTR && errno != EAGAIN) break;
        }
        if (master_open && !input.empty() && (events[1].revents & POLLOUT)) {
            const ssize_t count = write(master, input.data(), std::min<size_t>(input.size(), 4096));
            if (count > 0) input.erase(0, count);
            else if (count < 0 && errno != EAGAIN && errno != EINTR) break;
        }
        if (!output_buffer.empty() && (events[3].revents & POLLOUT)) {
            const ssize_t count = write(output, output_buffer.data(), std::min<size_t>(output_buffer.size(), 4096));
            if (count > 0) output_buffer.erase(0, count);
            else if (count < 0 && errno != EAGAIN && errno != EINTR) break;
        }
        if (exited && (!master_open || master < 0) && output_buffer.empty()) break;
        if (exited && master_open && polled == 0 && output_buffer.empty()) break;
    }
    if (private_output >= 0) close(private_output);
    return result;
}
/**
 * Shared client: forwards to the caller's own delivered image. Both images use
 * the existing Binder admission path under the caller's actual full UID.
 *
 * <p>Both facts are read from the process itself, so nothing is guessed: the executable
 * lives next to an `authorized` list, and the application's library directory is a mapped
 * `/data/app/<install>/lib/<abi>/` path.
 */
bool thin_shared_client() {
    char self[PATH_MAX] = {0};
    const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0) return false;
    std::string path(self, static_cast<size_t>(length));
    const size_t slash = path.rfind('/');
    if (slash == std::string::npos) return false;
    return access((path.substr(0, slash) + "/authorized").c_str(), R_OK) == 0;
}

/** Library directory of the application that is executing this client. */
std::string library_directory_from(const char* maps_path) {
    std::ifstream maps(maps_path);
    if (!maps) return "";
    std::string line;
    while (std::getline(maps, line)) {
        const size_t found = line.find("/data/app/");
        if (found == std::string::npos) continue;
        // The pathname is the LAST field of a maps line, so there is no trailing space to
        // cut at; searching for one made every line look path-less.
        std::string mapped = line.substr(found);
        while (!mapped.empty() && mapped.back() == 0x20) mapped.pop_back();
        const size_t lib = mapped.find("/lib/");
        if (lib == std::string::npos) continue;
        const size_t after = mapped.find('/', lib + 5);
        if (after == std::string::npos) continue;
        return mapped.substr(0, after);
    }
    return "";
}

std::string shared_authorization_list() {
    char self[PATH_MAX] = {0};
    const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0) return "";
    std::string path(self, static_cast<size_t>(length));
    const size_t slash = path.rfind('/');
    if (slash == std::string::npos) return "";
    const std::string candidate = path.substr(0, slash) + "/authorized";
    return access(candidate.c_str(), R_OK) == 0 ? candidate : "";
}

/** Resolve the Manager's nonsecret u/<fullUID> link for this caller. */
std::string directory_from_uid_table(int uid) {
    char self[PATH_MAX] = {0};
    const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0) return "";
    const std::string path(self, static_cast<size_t>(length));
    const size_t slash = path.rfind('/');
    if (slash == std::string::npos) return "";
    // One symlink per granted uid, inside a 0711 directory: this application can open the
    // entry named after its own uid, and cannot enumerate which applications are granted.
    char link[PATH_MAX] = {0};
    snprintf(link, sizeof(link), "%s/u/%d", path.substr(0, slash).c_str(), uid);
    char target[PATH_MAX] = {0};
    const ssize_t read = readlink(link, target, sizeof(target) - 1);
    if (read <= 0) return "";
    return std::string(target, static_cast<size_t>(read));
}

/**
 * Library directory of the application that launched this client.
 *
 * <p>The client is a freshly executed helper, so its own maps contain only itself.
 * The parent is the application process, and its libraries reveal the install path.
 */
std::string own_library_directory() {
    char parent_maps[64] = {0};    snprintf(parent_maps, sizeof(parent_maps), "/proc/%d/maps", static_cast<int>(getppid()));
    std::string found = library_directory_from(parent_maps);
    if (!found.empty()) return found;
    // The parent's libraries are the fast path, but an application with no native code has
    // none mapped at all. Fall back to its package name and the install directories, which
    // the application itself is allowed to read.
    // The caller may sit behind `sh -c`, so walk up a couple of ancestors until one of them
    // names a package.
    std::string name;
    pid_t ancestor = getppid();
    for (int step = 0; step < 3; ++step) {
        // Same-uid ancestors expose their maps, and an application process always has its
        // own libraries mapped - a far cheaper answer than scanning /data/app, which an
        // ordinary application is not allowed to list.
        char ancestor_maps[64] = {0};
        snprintf(ancestor_maps, sizeof(ancestor_maps), "/proc/%d/maps", static_cast<int>(ancestor));
        const std::string mapped = library_directory_from(ancestor_maps);
        if (!mapped.empty()) return mapped;
        char cmdline[64] = {0};
        snprintf(cmdline, sizeof(cmdline), "/proc/%d/cmdline", static_cast<int>(ancestor));
        const int descriptor = open(cmdline, O_RDONLY | O_CLOEXEC);
        if (descriptor < 0) break;
        char package[256] = {0};
        const ssize_t count = read(descriptor, package, sizeof(package) - 1);
        close(descriptor);
        if (count > 0) {
            const std::string candidate(package);
            if (!candidate.empty() && candidate[0] != '/') name = candidate;
        }
        if (!name.empty()) break;
        char stat_path[64] = {0};
        snprintf(stat_path, sizeof(stat_path), "/proc/%d/stat", static_cast<int>(ancestor));
        FILE* stat_file = fopen(stat_path, "r");
        if (stat_file == nullptr) break;
        int pid = 0, parent = 0;
        char comm[64] = {0};
        const int parsed = fscanf(stat_file, "%d %63s %*c %d", &pid, comm, &parent);
        fclose(stat_file);
        if (parsed != 3 || parent <= 1) break;
        ancestor = static_cast<pid_t>(parent);
    }
    if (name.empty()) return "";
    DIR* root = opendir("/data/app");
    if (root == nullptr) return "";
    std::string result;
    while (struct dirent* bucket = readdir(root)) {
        if (bucket->d_name[0] == '.') continue;
        const std::string bucket_path = std::string("/data/app/") + bucket->d_name;
        DIR* inner = opendir(bucket_path.c_str());
        if (inner == nullptr) continue;
        while (struct dirent* entry = readdir(inner)) {
            const std::string entry_name(entry->d_name);
            if (entry_name.rfind(name + "-", 0) != 0) continue;
            const std::string candidate = bucket_path + "/" + entry_name + "/lib/arm64";
            if (access((candidate + "/su").c_str(), X_OK) == 0) {
                result = candidate;
                break;
            }
        }
        closedir(inner);
        if (!result.empty()) break;
    }
    closedir(root);
    return result;
}


/** The list holds one decimal uid per line; a trailing `#` comment is ignored. */
bool caller_is_authorized(const std::string& list, int uid) {
    std::ifstream input(list);
    if (!input) return false;
    const std::string wanted = std::to_string(uid);
    std::string line;
    while (std::getline(input, line)) {
        const size_t comment = line.find('#');
        if (comment != std::string::npos) line.erase(comment);
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        if (line == wanted) return true;
    }
    return false;
}
}
namespace {
volatile sig_atomic_t g_resized = 0;
void note_resize(int) { g_resized = 1; }

/** Child of the supervisor; cancellation is forwarded here. */
volatile sig_atomic_t g_supervised_child = 0;
void forward_to_child(int signal) {
    // Escalate to SIGKILL for the command: cancellation must be final, and the child also
    // carries PR_SET_PDEATHSIG so it cannot survive this process by any other path.
    if (g_supervised_child > 0) kill(static_cast<pid_t>(g_supervised_child), SIGKILL);
    _exit(128 + signal);
}

/**
 * Runs the shell through a PTY and relays it, the way the upstream kit does.
 *
 * <p>Returns the child's exit status, or -1 when a PTY could not be created so the caller
 * can fall back to a direct exec. Keeping this in-process means no helper process is left
 * behind: the relay lives exactly as long as the command.
 */
int run_interactive(const std::string& executable, std::vector<char*>& arguments) {
    winsize size{};
    ioctl(STDIN_FILENO, TIOCGWINSZ, &size);

    const int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0) { close(master); return -1; }
    const char* slave_name = ptsname(master);
    if (slave_name == nullptr) { close(master); return -1; }

    const pid_t child = fork();
    if (child < 0) { close(master); return -1; }
    if (child == 0) {
        // A real session for the relay's child: the `setsid` symbol above is the no-op
        // that keeps the kit from detaching the caller, so ask the kernel directly.
        if (syscall(__NR_setsid) < 0) _exit(126);
        const int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        if (size.ws_row != 0) ioctl(slave, TIOCSWINSZ, &size);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        execv(executable.c_str(), arguments.data());
        _exit(127);
    }

    struct sigaction action {};
    action.sa_handler = note_resize;
    sigemptyset(&action.sa_mask);
    sigaction(SIGWINCH, &action, nullptr);

    Terminal terminal;
    const bool raw = isatty(STDIN_FILENO) && terminal.raw(STDIN_FILENO);
    const int flags = fcntl(master, F_GETFL);
    if (flags >= 0) fcntl(master, F_SETFL, flags | O_NONBLOCK);

    char buffer[4096];
    bool running = true;
    while (running) {
        if (g_resized) {
            g_resized = 0;
            winsize current{};
            if (ioctl(STDIN_FILENO, TIOCGWINSZ, &current) == 0) ioctl(master, TIOCSWINSZ, &current);
        }
        struct pollfd wait[2];
        wait[0] = {master, POLLIN, 0};
        wait[1] = {STDIN_FILENO, POLLIN, 0};
        const int ready = poll(wait, raw ? 2 : 1, 200);
        if (ready < 0 && errno != EINTR) break;
        if (ready > 0) {
            if (wait[0].revents & (POLLIN | POLLHUP)) {
                const ssize_t count = read(master, buffer, sizeof(buffer));
                if (count > 0) {
                    ssize_t written = 0;
                    while (written < count) {
                        const ssize_t step = write(STDOUT_FILENO, buffer + written, count - written);
                        if (step <= 0) break;
                        written += step;
                    }
                } else if (count == 0 || (errno != EAGAIN && errno != EINTR)) {
                    running = false;
                }
            }
            if (raw && (wait[1].revents & POLLIN)) {
                const ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
                if (count > 0) {
                    ssize_t written = 0;
                    while (written < count) {
                        const ssize_t step = write(master, buffer + written, count - written);
                        if (step <= 0) break;
                        written += step;
                    }
                } else if (count <= 0) {
                    running = false;
                }
            }
        }
        int status = 0;
        if (waitpid(child, &status, WNOHANG) == child) {
            // Drain whatever the child wrote before it exited.
            while (true) {
                const ssize_t count = read(master, buffer, sizeof(buffer));
                if (count <= 0) break;
                ssize_t written = 0;
                while (written < count) {
                    const ssize_t step = write(STDOUT_FILENO, buffer + written, count - written);
                    if (step <= 0) break;
                    written += step;
                }
            }
            close(master);
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return 1;
        }
    }
    close(master);
    int status = 0;
    waitpid(child, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}
}  // namespace

namespace {
/** Recorded for the invocation log only; never used for decisions. */
int g_argc = 0;
bool g_short_command = false;
}
int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);
    // Hand over to the application's delivered image before normal UID admission.
    if (thin_shared_client()) {
        // The Manager publishes u/<fullUID> as a link to that installation's library
        // directory. Read the exact caller's link before the existing own-process
        // fallback; this lookup does not enumerate another user's granted identities.
        std::string directory = directory_from_uid_table(static_cast<int>(getuid()));
        if (directory.empty()) directory = own_library_directory();
        if (directory.empty()) return fail("this application has no delivered client", 76);
        const std::string delivered = directory + "/su";
        if (access(delivered.c_str(), X_OK) != 0) return fail("this application is not authorized", 78);
        signal(SIGPIPE, SIG_DFL);
        execv(delivered.c_str(), argv);
        return fail("delivered client could not be started", 75);
    }
    // The caller must stay able to signal us after we escalate: a process whose real uid
    // is root cannot be signalled by the application that started it, so a cancelled
    // session would keep running. The real uid is restored before the final exec.
    const uid_t caller_uid = getuid();
    g_argc = argc;
    for (int probe = 1; probe < argc; ++probe) {
        if (std::strcmp(argv[probe], "-c") == 0) g_short_command = true;
    }
    maplesu::SuRequest request;
    bool login = false, preserve = false;
    std::string shell = "/system/bin/sh", command;
    bool has_command = false;
    bool has_user = false, options = true;
    int index = 1;
    while (index < argc) {
        const std::string option = argv[index];
        if (options && option == "--") { options = false; ++index; continue; }
        if (options && option == "-") { login = true; ++index; continue; }
        if (!options || option.empty() || option[0] != '-') {
            if (has_user) break;
            const passwd* entry = getpwnam(option.c_str());
            if (entry) request.uid = entry->pw_uid;
            else if (!number(option, request.uid)) return fail("unknown user", 64);
            has_user = true; ++index;
            // Android clients commonly place flags after USER: su 0 -c COMMAND.
            continue;
        }
        if (option == "-h" || option == "--help") {
            // Clients such as MT inspect --command here to choose their su invocation.
            puts("MapleSU\nUsage: su [OPTIONS] [USER [EXECUTABLE ARGS...]]\n"
                "  -c, --command COMMAND       Pass COMMAND to the invoked shell\n"
                "  -h, --help                  Display this help message\n"
                "  -, -l, --login              Start a login shell\n"
                "  -m, -p, --preserve-environment  Preserve the environment\n"
                "  -s, --shell SHELL           Use SHELL instead of /system/bin/sh\n"
                "  -M, -mm, --mount-master     Use the global mount namespace\n"
                "  -v, --version               Version name\n"
                "  -V                          Runtime protocol version\n"
                "Requires a granted installation."); return 0;
        }
        if (option == "-v" || option == "--version") { puts("1:MapleSU"); return 0; }
        if (option == "-V") { puts("1"); return 0; }
        if (option == "-l" || option == "--login") login = true;
        else if (option == "-m" || option == "-p" || option == "--preserve-environment") preserve = true;
        else if (option == "-M" || option == "-mm" || option == "--mount-master") { request.global_mount = 1; }
        else if (option == "-s" || option == "--shell") {
            if (++index >= argc) return fail("shell argument required", 64);
            shell = argv[index];
        } else if (option == "-c" || option == "--command" || option.rfind("--command=", 0) == 0) {
            if (option.rfind("--command=", 0) == 0) command = option.substr(10);
            else { if (++index >= argc) return fail("command argument required", 64); command = argv[index]; }
            has_command = true; ++index;
            // Android su implementations accept all remaining tokens as the shell command.
            for (; index < argc; ++index) { command += ' '; command += argv[index]; }
            break;
        } else return fail("unsupported su option", 64);
        ++index;
    }
    const std::string executable_name = !has_command && index < argc ? argv[index++] : shell;
    request.executable = resolve(executable_name);
    if (request.executable.empty())
        return fail((std::string("executable unavailable: ") + executable_name).c_str(), 127);
    request.arguments.push_back(login ? "-" : request.executable);
    if (has_command) { request.arguments.push_back("-c"); request.arguments.push_back(command); }
    else for (; index < argc; ++index) request.arguments.emplace_back(argv[index]);
    char directory[PATH_MAX];
    if (!getcwd(directory, sizeof(directory))) return fail("working directory unavailable", 71);
    request.directory = directory;
    for (char** entry = environ; entry && *entry; ++entry) {
        std::string variable = *entry;
        if (!preserve && (variable.rfind("HOME=", 0) == 0 ||
            variable.rfind("USER=", 0) == 0 || variable.rfind("LOGNAME=", 0) == 0 ||
            variable.rfind("SHELL=", 0) == 0)) continue;
        request.environment.push_back(variable);
    }
    if (!preserve) {
        const passwd* user = getpwuid(request.uid);
        const std::string home = user && user->pw_dir ? user->pw_dir : "/";
        const std::string name = user && user->pw_name ? user->pw_name : std::to_string(request.uid);
        request.environment.insert(request.environment.end(), {"HOME=" + home, "USER=" + name, "LOGNAME=" + name, "SHELL=" + shell});
        if (login) request.directory = home;
    }
    // PATH handling mirrors KSU `su.rs:318-319` (add_path_to_env(BINARY_DIR)):
    // the module tool directory is *appended* to the inherited PATH, so a module
    // command like `su -c resetprop ...` resolves the bundled tool while platform
    // binaries keep priority. `preserve` keeps the caller's PATH untouched, which
    // is the existing MapleSU escape hatch and is why the append is conditional.
    bool has_path = false;
    for (auto& variable : request.environment) {
        if (variable.rfind("PATH=", 0) == 0) {
            has_path = true;
            if (!preserve) {
                // Guarantee the platform directories instead of blindly appending.
                // An application process may hand us an empty or minimal PATH (Android
                // does not promise one), and `su -c 'id'` must work there: with a bare
                // `PATH=` the old code produced `PATH=:<module bin>`, so the shell could
                // not find `id`. Platform directories are appended, so a wired
                // application still resolves its own `su` first.
                std::string value = variable.substr(5);
                while (!value.empty() && value.front() == ':') value.erase(value.begin());
                if (value.find("/system/bin") == std::string::npos) {
                    if (!value.empty()) value += ':';
                    value += "/system/bin";
                }
                if (value.find("/system/xbin") == std::string::npos) {
                    if (!value.empty()) value += ':';
                    value += "/system/xbin";
                }
                if (value.find(maplesu::MODULE_BIN) == std::string::npos) {
                    if (!value.empty()) value += ':';
                    value += maplesu::MODULE_BIN;
                }
                variable = "PATH=" + value;
            }
        }
    }
    if (!has_path) {
        request.environment.push_back(std::string("PATH=/system/bin:/system/xbin:") + maplesu::MODULE_BIN);
    }
    size_t size = request.directory.size() + request.executable.size();
    for (const auto& value : request.arguments) size += value.size();
    for (const auto& value : request.environment) size += value.size();
    if (size > maplesu::MAX_FRAME || request.environment.size() > 512 || request.arguments.size() > 256) return fail("request too large", 64);
    for (int fd = 0; fd < 3; ++fd) if (isatty(fd)) request.terminals |= 1U << fd;
    window_size(request.terminals & 1 ? STDIN_FILENO : request.terminals & 2 ? STDOUT_FILENO : STDERR_FILENO, request);
    {
        const std::string shared = shared_authorization_list();
        if (!shared.empty()) {
            if (!caller_is_authorized(shared, static_cast<int>(getuid()))) {
                return fail("this application is not authorized for su", 78);
            }
        }
    }
    // Already-root module scripts keep conventional su semantics without consulting an app grant.
    if (geteuid() == 0) {
        if (request.global_mount) {
            const int namespace_fd = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
            if (namespace_fd < 0 || setns(namespace_fd, CLONE_NEWNS) != 0) return fail("global namespace unavailable", 71);
            close(namespace_fd);
        }
        if (chdir(request.directory.c_str()) != 0 || clearenv() != 0) return fail("root environment setup failed", 71);
        for (const auto& variable : request.environment) {
            const size_t separator = variable.find('=');
            if (setenv(variable.substr(0, separator).c_str(), variable.c_str() + separator + 1, 1) != 0)
                return fail("root environment setup failed", 71);
        }
        if (setgroups(0, nullptr) != 0 || setresgid(request.uid, request.uid, request.uid) != 0 ||
            setresuid(request.uid, request.uid, request.uid) != 0) return fail("identity setup failed", 71);
        // Keep the caller's real uid: a process whose real uid is root cannot be signalled by
        // the application that started it, so a cancelled session would keep running.
        if (request.uid == 0 && caller_uid != 0 && setresuid(caller_uid, 0, 0) != 0) {
            return fail("caller identity restore failed", 71);
        }
        std::vector<char*> arguments;
        for (auto& argument : request.arguments) arguments.push_back(argument.data());
        arguments.push_back(nullptr);
        signal(SIGPIPE, SIG_DFL);
        // A one-command session runs under a supervisor. Executing the shell directly would
        // make the escalated process the command itself: the caller can only signal this
        // process, so cancelling it killed the shell while the command it started was
        // reparented to init and kept running. The supervisor forwards cancellation and the
        // child dies with it.
        if (has_command) {
            const pid_t supervisor = fork();
            if (supervisor == 0) {
                prctl(PR_SET_PDEATHSIG, SIGKILL);
                execv(request.executable.c_str(), arguments.data());
                _exit(errno == ENOENT ? 127 : 126);
            }
            if (supervisor < 0) return fail("supervisor creation failed", 71);
            for (int forwarded : {SIGTERM, SIGINT, SIGHUP, SIGQUIT}) {
                struct sigaction action {};
                action.sa_handler = forward_to_child;
                action.sa_flags = SA_RESTART;
                sigemptyset(&action.sa_mask);
                sigaction(forwarded, &action, nullptr);
            }
            g_supervised_child = supervisor;
            int status = 0;
            while (waitpid(supervisor, &status, 0) < 0 && errno == EINTR) {
            }
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return 1;
        }
        if (!has_command && isatty(STDIN_FILENO)) {
            const int relayed = run_interactive(request.executable, arguments);
            if (relayed >= 0) return relayed;
        }
        execv(request.executable.c_str(), arguments.data());
        return fail("exec failed", errno == ENOENT ? 127 : 126);
    }
    // Refuse to bootstrap for anything that is not a real application identity. The
    // helper must run as the caller's own UID: the Root endpoint authorizes this
    // exact installation by the real Binder caller UID, and shell/system callers
    // can never hold a grant.
    const uint32_t caller = getuid();
    if (caller < 10000 || caller != geteuid()) return fail("an application identity is required");
    std::string apk, library;
    if (!derive_layout(apk, library)) return fail("runtime deployment is not ready");
    Bootstrap bootstrap;
    if (!bootstrap.start(caller, apk, library, request.terminals)) return fail("Binder helper unavailable");
    const int socket = bootstrap.control;
    timeval timeout{15, 0};
    setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    if (!maplesu::write_u32(socket, frame_size(request)) || !request.write_to(socket)) return fail("request transmission failed");
    char response;
    if (!maplesu::read_all(socket, &response, 1)) return fail("runtime unavailable");
    if (response == 'D') return fail("request denied", 77);
    if (response != 'R') return fail("runtime unavailable");
    int master = -1;
    if (request.terminals && !maplesu::receive_fds(socket, 'P', &master, 1)) return fail("PTY descriptor unavailable", 71);
    timeout = {0, 0}; setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    const int result = relay(socket, master, request);
    if (master >= 0) close(master);
    return result;
}
