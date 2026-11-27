// webui_loader -- installed WebUI host program.
//
// The program is one half of the SDK's module WebUI feature.  The kernel-side
// kit validates the module, allocates a localhost port and starts this process as
// root; this process then
//
//   1. consumes the authenticated worker deployment directory,
//   2. maps the module ELF (the same file the kernel runs) and resolves the
//      module's three WebUI entry points,
//   3. asks the module to start its CivetWeb server on the allocated port, and
//      reads the port back from the module so the launch URL is the real one,
//   4. keeps the documented static webroot contract alive when a module brings no
//      server of its own,
//   5. opens the UI in the device browser / manager and watches the requesting
//      process, honouring the module's exit policy when it disappears.
//
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>

#include <fcntl.h>

#include <signal.h>
#include <sys/types.h>
#include <unistd.h>

#include "contract.h"
#include "module_err_def.h"
#include "module_host.h"
#include "parent_watch.h"
#include "payload_support.h"
#include "root_grant.h"
#include "root_key.h"
#include "webroot_server.h"

namespace {

using maplesu::payload::err_printf;
using maplesu::payload::out_printf;
using maplesu::payload::read_file;
using maplesu::payload::webui::LaunchArgs;
using maplesu::payload::webui::ModuleHost;
using maplesu::payload::webui::ServerConfig;
using maplesu::payload::webui::WebRootServer;

volatile sig_atomic_t g_stop_signal = 0;

constexpr long long as_code(KModErr err) { return static_cast<long long>(err); }

std::string error_name(long long code) {
    return to_string(static_cast<KModErr>(code));
}

// ---------------------------------------------------------------------------
// async-signal-safe trace (the handler must not use stdio buffers)
// ---------------------------------------------------------------------------
void write_signal_notice(int signo) {
    char buffer[96];
    const char* prefix = "\n[webui_loader] *** Caught signal ";
    const char* suffix = " ***\n";
    size_t length = 0;
    for (const char* p = prefix; *p != '\0' && length < sizeof(buffer) - 24; ++p)
        buffer[length++] = *p;
    if (signo >= 10) buffer[length++] = static_cast<char>('0' + (signo / 10) % 10);
    buffer[length++] = static_cast<char>('0' + signo % 10);
    for (const char* p = suffix; *p != '\0' && length < sizeof(buffer); ++p)
        buffer[length++] = *p;
    const ssize_t ignored = ::write(STDERR_FILENO, buffer, length);
    (void)ignored;
}

void on_signal(int signo) {
    g_stop_signal = signo;
    write_signal_notice(signo);
    _exit(0);
}

void install_signal_handlers() {
    struct sigaction action {};
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    action.sa_flags = 0;
    for (int signo : {SIGINT, SIGTERM, SIGHUP, SIGQUIT}) {
        sigaction(signo, &action, nullptr);
    }
    struct sigaction ignore {};
    ignore.sa_handler = SIG_IGN;
    sigemptyset(&ignore.sa_mask);
    sigaction(SIGPIPE, &ignore, nullptr);
}

// ---------------------------------------------------------------------------
// launching the browser / manager UI
// ---------------------------------------------------------------------------
// The kit's userland path opens the localhost UI with the platform activity
// starter; `am` is present on every device that runs this program.
void open_url(const std::string& url) {
    const std::string command =
        "am start -a android.intent.action.VIEW -d " + url;
    FILE* pipe = ::popen(command.c_str(), "r");
    if (pipe == nullptr) {
        err_printf("[webui_loader] open url command failed: %s\n", strerror(errno));
        return;
    }
    char drain[256];
    while (::fgets(drain, sizeof(drain), pipe) != nullptr) {
        // the activity starter's output is not part of the contract
    }
    ::pclose(pipe);
}

}  // namespace

int main(int argc, char* argv[]) {
    // The kit wires the loader's stdio to its log consumer; line buffering keeps
    // every trace line visible immediately, like the reference program.
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    install_signal_handlers();

    LaunchArgs args;
    std::string parse_error;
    if (!maplesu::payload::webui::parse_launch_args(argc, argv, args, parse_error)) {
        err_printf("webui_loader: %s\n", parse_error.c_str());
        return 2;
    }
    // Do this before loading module code: a module's exec descendants must not
    // retain our startup writer and turn a loader failure into an open pipe.
    if (args.ready_fd >= 0) {
        const int flags = ::fcntl(args.ready_fd, F_GETFD);
        if (flags < 0 || ::fcntl(args.ready_fd, F_SETFD, flags | FD_CLOEXEC) < 0) return 2;
    }
    maplesu::payload::webui::DeploymentDirectory deployment;
    const bool owner_launch = args.owner_directory_fd >= 0;
    const auto report_startup = [&args, &deployment, owner_launch](long long error, int port) {
        bool sent = maplesu::payload::webui::write_startup_receipt(args.ready_fd, error, port);
        if (sent && error == as_code(KModErr::OK) && owner_launch)
            sent = maplesu::payload::webui::write_deployment_directory(args.ready_fd, deployment);
        if (args.ready_fd >= 0) ::close(args.ready_fd);
        args.ready_fd = -1;
        return sent;
    };

    maplesu::payload::webui::LegacyCredential credential;
    long long root_err;
    if (owner_launch) {
        const int descriptor = args.owner_directory_fd;
        bool received = maplesu::payload::webui::read_deployment_directory(descriptor, deployment);
        if (received && args.legacy_credential_record)
            received = maplesu::payload::webui::read_legacy_credential(descriptor, credential);
        ::close(descriptor);
        args.owner_directory_fd = -1;
        root_err = !received ? as_code(KModErr::ERR_READ_CHILD_DATA) :
            maplesu::payload::webui::acquire_root();
    } else {
        root_err = !maplesu::payload::webui::read_installed_legacy_credential(credential.value)
            ? as_code(KModErr::ERR_MODULE_ROOT_KEY)
            : maplesu::payload::webui::acquire_legacy_root(credential.value);
    }
    if (root_err != as_code(KModErr::OK)) {
        maplesu::payload::webui::clear_legacy_credential(credential);
        err_printf("get_root failed.\n");
        report_startup(root_err, 0);
        return 1;
    }

    const std::string data_directory = owner_launch
        ? maplesu::payload::webui::deployment_data_dir(deployment)
        : "/data/" + std::string(credential.value, 16) + credential.value[0] + "/";
    const std::string private_dir = data_directory + "modules/" + args.mod_id32 + "/";
    const std::string elf_path = private_dir + args.mod_id32;
    const std::string webroot_dir = private_dir + "webroot";

    ModuleHost module;
    int port = args.port;
    long long server_err = as_code(KModErr::ERR_MODULE_WEB_UI_LOADER_NOT_EXIST);
    if (private_dir.empty() || elf_path.empty()) {
        server_err = as_code(KModErr::ERR_MODULE_ROOT_KEY);
    } else {
        // The module runs with its private directory as the working directory
        // (its webroot and data files are addressed relatively).
        if (::chdir(private_dir.c_str()) != 0) {
            maplesu::payload::webui::clear_legacy_credential(credential);
            report_startup(as_code(KModErr::ERR_MODULE_OPEN_DIR), 0);
            return 1;
        }
        const long long load_err = module.load(elf_path);
        if (load_err != as_code(KModErr::OK)) {
            server_err = load_err;
        } else {
            server_err = module.start_web_ui(private_dir.c_str(),
                                             webroot_dir.c_str(),
                                             args.mod_id32.c_str(), args.port,
                                             credential.value);
            maplesu::payload::webui::clear_legacy_credential(credential);
            out_printf("[webui_loader] start_module_web_ui_server -> rc=%s\n",
                       error_name(server_err).c_str());
            if (server_err == as_code(KModErr::OK)) {
                // A module server may bind a different port than the requested
                // one; its answer is authoritative for the launch URL.
                int published = 0;
                const long long port_error = module.last_server_port(published);
                if (port_error != as_code(KModErr::OK) || published <= 0 || published > 65535) {
                    const long long error = port_error != as_code(KModErr::OK)
                        ? port_error : as_code(KModErr::ERR_MODULE_WEB_UI_SERVER_NOT_RUNNING);
                    report_startup(error, 0);
                    return 1;
                }
                port = published;
            }
        }
    }
    maplesu::payload::webui::clear_legacy_credential(credential);
    if (server_err != as_code(KModErr::OK)) {
        out_printf("[webui_loader] open_module_web_ui -> rc=%s\n",
                   error_name(server_err).c_str());
    }

    // Static webroot fallback: only when the module did not bring a server.  The
    // module keeps ownership of POST routes and its own dynamic GET handlers in
    // that case; the loader guarantees the documented file-serving contract.
    WebRootServer fallback;
    if (server_err != as_code(KModErr::OK)) {
        if (server_err == as_code(KModErr::ERR_MODULE_ROOT_KEY)) {
            report_startup(server_err, 0);
            return 1;
        }
        ServerConfig config;
        config.document_root = webroot_dir;
        config.port = port;
        if (!fallback.start(config)) {
            err_printf("[webui_loader] webroot fallback server unavailable (webroot=%s)\n",
                       webroot_dir.c_str());
            report_startup(as_code(KModErr::ERR_MODULE_WEB_UI_SERVER_NOT_RUNNING), 0);
            return 1;
        }
    }

    if (!report_startup(as_code(KModErr::OK), port)) return 1;

    out_printf("[webui_loader] WebUI loader started, mod_id32: %s, port: %d, pid: %d, ppid: %d\n",
               args.mod_id32.c_str(), port, static_cast<int>(::getpid()),
               static_cast<int>(::getppid()));

    const std::string url = "http://127.0.0.1:" + std::to_string(port);
    if (args.open_browser) {
        out_printf("[webui_loader] start open url: %s\n", url.c_str());
        open_url(url);
    }

    out_printf("[webui_loader] waiting for the parent process to exit...");
    maplesu::payload::webui::wait_for_owner_exit(args.caller_pid);

    // The module decides whether the UI process must stop with its owner.  An
    // unanswered policy keeps the conservative behaviour: stop.
    unsigned int action = 0;
    bool keep_running = false;
    if (module.loaded() &&
        module.before_server_exit_action(action) == as_code(KModErr::OK)) {
        out_printf("[webui_loader] get before exit action: %d\n",
                   static_cast<int>(action));
        keep_running = (action == 1);
    }

    if (!keep_running) {
        out_printf("[webui_loader] the parent process has exited, bye bye!");
        fallback.stop();
        _exit(0);
    }

    // KeepRunning: the module owns a background service that outlives its owner.
    // The process keeps serving until a signal asks it to stop.
    while (g_stop_signal == 0) {
        ::sleep(60);
    }
    fallback.stop();
    return 0;
}
