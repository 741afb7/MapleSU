#include "webui_lifecycle.h"
#include "../../maplesu-sdk/sdk/include/maplesu_request_private.h"
#include "maplesu_sdk/features/maplesu_module_webui_loader.h"
#include "../../maplesu-sdk/sdk/src/maplesu_core/module_webui_host.h"
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/wait.h>
#include <unistd.h>

namespace maplesu {
namespace {
KModErr start_detached_module_webui(const char* id, int& port,
                                  std::string* module_directory) {
    // The SDK loader watches its creator and repeatedly runs device adaptation
    // until that creator exits. A short-lived creator hands server lifetime to
    // the module's KeepRunning callback instead. Deployment facts travel through the private pipe.
    struct Receipt {
        KModErr error;
        int port;
        char module_directory[128];
    } receipt{KModErr::ERR_READ_CHILD_ERRCODE, 0, {}};
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) return admission;
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return receipt.error;
    const pid_t worker = fork();
    if (worker == 0) {
        close(fds[0]);
        try {
            if (module_directory) {
                std::string directory;
                receipt.error = maplesu::sdk::features::web_ui::start_module_web_ui_server_for_manager(
                    id, receipt.port, directory);
                if (is_ok(receipt.error)) {
                    if (directory.empty() || directory.size() >= sizeof(receipt.module_directory)) {
                        receipt.error = KModErr::ERR_READ_CHILD_DATA;
                    } else {
                        std::memcpy(receipt.module_directory, directory.c_str(), directory.size() + 1);
                    }
                }
            } else {
                receipt.error = maplesu::sdk::features::web_ui::start_module_web_ui_server_async(id, receipt.port);
            }
        } catch (...) {}
        ssize_t count;
        do { count = write(fds[1], &receipt, sizeof(receipt)); } while (count < 0 && errno == EINTR);
        _exit(count == sizeof(receipt) ? 0 : 1);
    }
    close(fds[1]);
    if (worker < 0) { close(fds[0]); return receipt.error; }
    pollfd event{fds[0], POLLIN, 0};
    int ready;
    do { ready = poll(&event, 1, 10000); } while (ready < 0 && errno == EINTR);
    ssize_t count = -1;
    if (ready > 0) {
        do { count = read(fds[0], &receipt, sizeof(receipt)); } while (count < 0 && errno == EINTR);
    }
    close(fds[0]);
    // Only this unreaped child is eligible for cleanup; never use a stored PID.
    if (count != sizeof(receipt)) kill(worker, SIGKILL);
    while (waitpid(worker, nullptr, 0) < 0 && errno == EINTR) {}
    if (count != sizeof(receipt)) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (is_failed(receipt.error)) return receipt.error;
    if (module_directory) {
        const auto length = strnlen(receipt.module_directory, sizeof(receipt.module_directory));
        if (length == 0 || length >= sizeof(receipt.module_directory)) return KModErr::ERR_READ_CHILD_DATA;
        module_directory->assign(receipt.module_directory, length);
    }
    port = receipt.port;
    return receipt.error;
}
}

KModErr start_detached_module_webui(const char* id, int& port) {
    return start_detached_module_webui(id, port, nullptr);
}

KModErr start_detached_module_webui(const char* id, int& port,
                                 std::string& module_directory) {
    return start_detached_module_webui(id, port, &module_directory);
}
}
