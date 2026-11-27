// module web ui server implementation.
//
#include "kernel_module_kit_umbrella.h"

#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sched.h>
#include <string>
#include <sys/resource.h>
#include <unistd.h>
#include <vector>

#include "module_web_ui_http_handler.h"
#include "private_file_utils.h"
#include "private_maple_runtime.h"

extern "C" void __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(const char* mod_id32);

namespace PerfCoreKit {

// Picks the CPU frequencies of the performance cluster from sysfs: every core
// whose cpuinfo_max_freq is above the midpoint between the lowest and highest
// observed frequency belongs to it. When every core reports the same frequency
// (or none can be read), the whole mask is returned.
inline cpu_set_t get_performance_cluster_mask() {
    cpu_set_t cpu_mask;
    CPU_ZERO(&cpu_mask);
    const int cpu_count = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    if (cpu_count < 1) {
        return cpu_mask;
    }
    std::vector<long> frequencies(static_cast<size_t>(cpu_count), 0);
    long min_frequency = -1;
    long max_frequency = 0;
    for (size_t cpu = 0; cpu < static_cast<size_t>(cpu_count); ++cpu) {
        const std::string frequency_path =
            "/sys/devices/system/cpu/cpu" + std::to_string(static_cast<int>(cpu)) +
            "/cpufreq/cpuinfo_max_freq";
        std::ifstream stream(frequency_path, std::ios::in);
        if (!stream) {
            continue;
        }
        long frequency = 0;
        stream >> frequency;
        if ((stream.rdstate() & (std::ios::badbit | std::ios::failbit)) != 0) {
            continue;
        }
        frequencies[cpu] = frequency;
        if (min_frequency == -1 || frequency < min_frequency) {
            min_frequency = frequency;
        }
        max_frequency = std::max(frequency, max_frequency);
    }
    if (max_frequency == 0 || max_frequency == min_frequency) {
        for (size_t cpu = 0; cpu < static_cast<size_t>(cpu_count); ++cpu) {
            CPU_SET(cpu, &cpu_mask);
        }
        return cpu_mask;
    }
    const long threshold = min_frequency + (max_frequency - min_frequency) / 2;
    for (size_t cpu = 0; cpu < static_cast<size_t>(cpu_count); ++cpu) {
        if (frequencies[cpu] > threshold) {
            CPU_SET(cpu, &cpu_mask);
        }
    }
    return cpu_mask;
}

// Raises the current thread priority and pins it to the performance cluster.
inline bool optimize_current_thread() {
    const pid_t tid = gettid();
    setpriority(PRIO_PROCESS, tid, -10);
    const cpu_set_t cpu_mask = get_performance_cluster_mask();
    if (__sched_cpucount(sizeof(cpu_mask), &cpu_mask) > 0) {
        return sched_setaffinity(tid, sizeof(cpu_mask), &cpu_mask) == 0;
    }
    return false;
}

}  // namespace PerfCoreKit

void* g_handler = nullptr;   // kernel_module::WebUIHttpHandler*
void* g_server = nullptr;    // CivetServer*
uint32_t g_server_port = 0;  // last successfully listening port

KModErr unsafe_module_start_web_ui_server(const char* module_private_dir,
                                          const char* module_webroot_dir,
                                          const char* mod_id32,
                                          kernel_module::WebUIHttpHandler* handler,
                                          int port) {
    if (g_server != nullptr) {
        return KModErr::ERR_MODULE_WEB_UI_SERVER_RUNNING;
    }
    if (handler == nullptr && !file_utils::file_exists(module_webroot_dir)) {
        return KModErr::ERR_MODULE_WEB_UI_SERVER_NOT_EXIST;
    }
    __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(mod_id32);
    PerfCoreKit::optimize_current_thread();
    g_handler = handler;
    g_server_port = static_cast<uint32_t>(port);
    std::string port_text = std::to_string(port);
    if (handler != nullptr) {
        handler->onPrepareCreate(module_private_dir, static_cast<uint32_t>(port));
    }
    std::vector<std::string> option_storage;
    option_storage.reserve(12);
    option_storage.emplace_back("listening_ports");
    option_storage.emplace_back(port_text);
    if (file_utils::file_exists(module_webroot_dir)) {
        option_storage.emplace_back("document_root");
        option_storage.emplace_back(module_webroot_dir);
        option_storage.emplace_back("index_files");
        option_storage.emplace_back("index.html,index.htm");
        option_storage.emplace_back("enable_directory_listing");
        option_storage.emplace_back("no");
    }
    option_storage.emplace_back("num_threads");
    option_storage.emplace_back("4");
    std::vector<const char*> options;
    options.reserve(option_storage.size() + 1);
    for (const std::string& option : option_storage) {
        options.push_back(option.c_str());
    }
    options.push_back(nullptr);
    CivetCallbacks callbacks;
    std::memset(&callbacks, 0, sizeof(callbacks));
    callbacks.init_thread = [](const mg_context*, int) -> void* {
        PerfCoreKit::optimize_current_thread();
        return nullptr;
    };
    g_server = new CivetServer(options.data(), &callbacks, nullptr);
    if (handler != nullptr) {
        CivetServer* const server = static_cast<CivetServer*>(g_server);
        handler->onServerCreated(server);
        server->addHandler(std::string("/"), handler);
    }
    return KModErr::OK;
}

KModErr unsafe_module_get_last_web_ui_server_port(int& out_port) {
    if (g_server == nullptr) {
        return KModErr::ERR_MODULE_WEB_UI_SERVER_NOT_RUNNING;
    }
    out_port = static_cast<int>(g_server_port);
    return KModErr::OK;
}

KModErr unsafe_module_get_before_server_exit_action(
    kernel_module::WebUIHttpHandler::ServerExitAction& out_action) {
    if (g_server == nullptr) {
        return KModErr::ERR_MODULE_WEB_UI_SERVER_NOT_RUNNING;
    }
    kernel_module::WebUIHttpHandler::ServerExitAction action =
        kernel_module::WebUIHttpHandler::ServerExitAction::Exit;
    kernel_module::WebUIHttpHandler* const handler =
        static_cast<kernel_module::WebUIHttpHandler*>(g_handler);
    if (handler != nullptr) {
        action = handler->onBeforeServerExit();
    }
    out_action = action;
    return KModErr::OK;
}
