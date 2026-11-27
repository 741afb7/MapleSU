
//
#include "../compat/webui_legacy_abi.h"
#include "../module/operations.h"
#include "../module/path_contract.h"
#include "../../maplesu_core/module_webui_host.h"
#include "../../payload/webui_loader/src/startup_receipt.h"
#include "maplesu_storage_worker_private.h"
#include "module_base_kernel_request.h"
#include "loader_compatibility.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>
#include <chrono>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/wait.h>

#include "../compat/module_webui_exports_legacy_abi.h"

namespace maplesu::webui {
namespace {

using maplesu::payload::webui::DeploymentDirectory;
using maplesu::payload::webui::StartupReceipt;
using StartupClock = std::chrono::steady_clock;

// Resolve paths only while this real worker owns the authenticated storage scope.
static KModErr prepare_loader(const char* module_id32,
                             std::string& loader_path, DeploymentDirectory& directory,
                             bool& log_enabled, bool& send_credential) {
    storage::WorkerScope worker(module_id32);
    RETURN_IF_ERROR(worker.root());

    std::string system_directory;
    RETURN_IF_ERROR(storage::current_system_directory(system_directory));
    const auto modules = std::filesystem::path(system_directory) / "modules";
    loader_path = (modules / "webui_loader").string();
    {
        storage::StorageContext context;
        RETURN_IF_ERROR(storage::current_context(context));
        std::memcpy(directory.name, context.locator.kernel_component, sizeof(directory.name));
        if (!maplesu::payload::webui::valid_directory(directory))
            return KModErr::ERR_MODULE_ROOT_KEY;
    }

    std::error_code exists_error;
    if (!std::filesystem::exists(std::filesystem::path(loader_path), exists_error))
        return KModErr::ERR_MODULE_WEB_UI_LOADER_NOT_EXIST;
    const char* credential = kernel_module::current_module_root_key();
    if (credential && *credential) {
        std::ifstream loader(loader_path, std::ios::binary);
        if (!loader) return KModErr::ERR_MODULE_WEB_UI_LOADER_NOT_EXIST;
        std::string image((std::istreambuf_iterator<char>(loader)), std::istreambuf_iterator<char>());
        if (loader.bad()) return KModErr::ERR_MODULE_WEB_UI_LOADER_NOT_EXIST;
        const bool compatible = select_legacy_handoff(image, credential, send_credential);
        volatile char* bytes = image.data();
        for (size_t i = 0; i < image.size(); ++i) bytes[i] = 0;
        if (!compatible) return KModErr::ERR_MODULE_ROOT_KEY;
    }
    const std::string module_elf_path = (modules / module_id32 / module_id32).string();
    std::ifstream module_elf(module_elf_path.c_str(), std::ios::binary);
    if (!module_elf) return KModErr::ERR_MODULE_OPEN_DIR;
    module_elf.seekg(0, std::ios::end);
    const std::streamoff elf_size = module_elf.tellg();
    if (elf_size < 0) return KModErr::ERR_MODULE_OPEN_DIR;
    module_elf.seekg(0, std::ios::beg);
    std::vector<uint8_t> elf_bytes(static_cast<size_t>(elf_size));
    module_elf.read(reinterpret_cast<char*>(elf_bytes.data()), elf_size);
    if (!module_elf) return KModErr::ERR_MODULE_OPEN_DIR;
    module::Descriptor descriptor{};
    RETURN_IF_ERROR(module::parse_metadata(elf_bytes, descriptor));
    if (!descriptor.web_ui) return KModErr::ERR_MODULE_WEB_UI_SERVER_NOT_EXIST;
    log_enabled = compat::webui_log_enabled();
    return worker.close_checked();
}

static void reap_loader(pid_t pid, bool terminate) {
    if (pid <= 0) return;
    if (terminate) ::kill(pid, SIGKILL);
    while (::waitpid(pid, nullptr, 0) < 0 && errno == EINTR) {}
}

static void* reap_background_loader(void* opaque) {
    reap_loader(static_cast<pid_t>(reinterpret_cast<intptr_t>(opaque)), false);
    return nullptr;
}

static bool observe_loader_exit(pid_t pid) {
    pthread_attr_t attributes;
    if (pthread_attr_init(&attributes) != 0) return false;
    if (pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED) != 0) {
        pthread_attr_destroy(&attributes);
        return false;
    }
    pthread_t thread;
    const int error = pthread_create(&thread, &attributes, reap_background_loader,
                                    reinterpret_cast<void*>(static_cast<intptr_t>(pid)));
    pthread_attr_destroy(&attributes);
    return error == 0;
}

static bool read_startup_data(int fd, void* data, size_t remaining,
                              StartupClock::time_point deadline) {
    char* cursor = static_cast<char*>(data);
    while (remaining != 0) {
        const auto milliseconds = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - StartupClock::now()).count();
        if (milliseconds <= 0) return false;
        pollfd pending{fd, POLLIN, 0};
        const int ready = ::poll(&pending, 1, static_cast<int>(milliseconds));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (pending.revents & (POLLERR | POLLNVAL)) != 0) return false;
        const ssize_t count = ::read(fd, cursor, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        remaining -= static_cast<size_t>(count);
    }
    return true;
}

static bool inherit_descriptor(int descriptor) {
    const int flags = ::fcntl(descriptor, F_GETFD);
    return flags >= 0 && ::fcntl(descriptor, F_SETFD, flags & ~FD_CLOEXEC) >= 0;
}

static KModErr start_module_web_ui_server(const char* mod_id32,
                                          int& out_port, std::string* out_module_dir) {
    if (mod_id32 == nullptr ||
        strnlen(mod_id32, sizeof(module::Descriptor::id32)) >= sizeof(module::Descriptor::id32) ||
        !module_paths::valid_id(mod_id32)) return KModErr::ERR_MODULE_PARAM;

    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    CpuPinGuardAuto cpu_pin_guard(false);
    const int32_t caller_pid = static_cast<int32_t>(getpid());
    srand(static_cast<unsigned int>(time(nullptr)));
    const int port = 20000 + rand() % 40001;
    compat::fork_pipe_info pipe;
    if (compat::fork_pipe_child_process(pipe)) {
        KModErr err = KModErr::OK;
        int system_error = 0;
        try {
            std::string loader_path;
            DeploymentDirectory directory;
            bool log_enabled = false;
            bool send_credential = false;
            err = prepare_loader(mod_id32, loader_path, directory, log_enabled, send_credential);
            // prepare_loader has retired the storage scope and worker session.
            compat::fork_base_info::safe_close(pipe.fd_parent_to_child_read);
            if (is_ok(err)) {
                // fork_pipe_child_process closed the original child's write end.
                // The optional historical credential has its own versioned record.
                int handoff[2] = {-1, -1};
                if (::pipe2(handoff, O_CLOEXEC) != 0) {
                    system_error = errno;
                    err = KModErr::ERR_READ_CHILD_DATA;
                } else {
                    pipe.fd_parent_to_child_read = handoff[0];
                    pipe.fd_parent_to_child_write = handoff[1];
                    if (!maplesu::payload::webui::write_deployment_directory(handoff[1], directory)) {
                        system_error = errno;
                        err = KModErr::ERR_READ_CHILD_DATA;
                    }
                    if (is_ok(err) && send_credential) {
                        maplesu::payload::webui::LegacyCredential credential;
                        const char* configured = kernel_module::current_module_root_key();
                        if (!maplesu::compat::native_module::valid_legacy_credential(configured)) {
                            err = KModErr::ERR_MODULE_ROOT_KEY;
                        } else {
                            std::memcpy(credential.value, configured, sizeof(credential.value));
                            if (!maplesu::payload::webui::write_legacy_credential(handoff[1], credential)) {
                                system_error = errno;
                                err = KModErr::ERR_READ_CHILD_DATA;
                            }
                        }
                        maplesu::payload::webui::clear_legacy_credential(credential);
                    }
                    compat::fork_base_info::safe_close(pipe.fd_parent_to_child_write);
                    if (is_ok(err) && !inherit_descriptor(pipe.fd_parent_to_child_read)) {
                        system_error = errno;
                        err = KModErr::ERR_MODULE_FCNTL;
                    }
                }
            }
            if (is_ok(err) && !inherit_descriptor(pipe.fd_child_to_parent_write)) {
                system_error = errno;
                err = KModErr::ERR_MODULE_FCNTL;
            }
            if (is_ok(err)) {
                const std::string port_text = std::to_string(port);
                const std::string caller_pid_text = std::to_string(caller_pid);
                const std::string ready_fd_text = std::to_string(pipe.fd_child_to_parent_write);
                const std::string directory_fd_text = std::to_string(pipe.fd_parent_to_child_read);
                const char* loader_argv[] = {
                    loader_path.c_str(), mod_id32, port_text.c_str(), caller_pid_text.c_str(),
                    log_enabled ? "1" : "0", maplesu::payload::webui::kReadyArgument,
                    ready_fd_text.c_str(), out_module_dir == nullptr ? "browser" : "manager",
                    send_credential ? maplesu::payload::webui::kLegacyDirectoryArgument
                                    : maplesu::payload::webui::kOwnerDirectoryArgument,
                    directory_fd_text.c_str(), nullptr,
                };
                execve(loader_path.c_str(), const_cast<char* const*>(loader_argv), environ);
                system_error = errno;
                err = KModErr::ERR_MODULE_WEB_UI_LOADER_NOT_EXIST;
            }
        } catch (...) {
            err = KModErr::ERR_MODULE_ALLOC_MEM;
        }
        maplesu::payload::webui::write_startup_receipt(
            pipe.fd_child_to_parent_write, static_cast<int64_t>(err), 0, system_error);
        pipe.close_all();
        _exit(1);
    }

    if (pipe.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;
    KModErr err = KModErr::OK;
    StartupReceipt receipt;
    DeploymentDirectory directory;
    std::string module_directory;
    try {
        const auto deadline = StartupClock::now() + std::chrono::seconds(10);
        if (!read_startup_data(pipe.fd_child_to_parent_read, &receipt, sizeof(receipt), deadline) ||
            !maplesu::payload::webui::valid_receipt(receipt)) {
            err = KModErr::ERR_READ_CHILD_ERRCODE;
        } else {
            err = static_cast<KModErr>(receipt.error);
            if (is_ok(err) &&
                (!read_startup_data(pipe.fd_child_to_parent_read, &directory, sizeof(directory), deadline) ||
                 !maplesu::payload::webui::valid_directory(directory)))
                err = KModErr::ERR_READ_CHILD_DATA;
        }
        if (is_ok(err) && out_module_dir != nullptr) {
            std::string name(directory.name, sizeof(directory.name));
            name.push_back(name[0]);
            module_directory = "/data/" + name + "/modules/" + mod_id32 + "/";
        }
    } catch (...) {
        err = KModErr::ERR_MODULE_ALLOC_MEM;
    }
    pipe.close_all();
    if (is_failed(err)) {
        reap_loader(pipe.child_pid, true);
        return err;
    }
    if (!observe_loader_exit(pipe.child_pid)) {
        reap_loader(pipe.child_pid, true);
        return KModErr::ERR_MODULE_ALLOC_MEM;
    }
    if (out_module_dir != nullptr) out_module_dir->swap(module_directory);
    out_port = receipt.port;
    return err;
}

}  // namespace
}  // namespace maplesu::webui

namespace maplesu::sdk::features::web_ui {

KModErr start_module_web_ui_server_async(const char* mod_id32,
                                         int& out_port) {
    return ::maplesu::webui::start_module_web_ui_server(mod_id32, out_port, nullptr);
}

KModErr start_module_web_ui_server_for_manager(const char* mod_id32,
                                              int& out_port, std::string& out_module_dir) {
    return ::maplesu::webui::start_module_web_ui_server(mod_id32, out_port, &out_module_dir);
}

}  // namespace maplesu::sdk::features::web_ui

namespace skroot_env::features::web_ui {

KModErr start_module_web_ui_server_async(const char* mod_id32,
                                         int& out_port) {
    return maplesu::sdk::features::web_ui::start_module_web_ui_server_async(
        mod_id32, out_port);
}

}  // namespace skroot_env::features::web_ui
