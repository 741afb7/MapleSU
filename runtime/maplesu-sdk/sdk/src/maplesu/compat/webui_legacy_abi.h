// webui loader private implementation.
//
#pragma once

#include "module_err_def.h"
#include "maplesu_sdk/maplesu_log.h"
#include "fork_legacy_abi.h"

#include <sched.h>
#include <unistd.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace skroot_env::features::web_ui {
KModErr start_module_web_ui_server_async(const char* mod_id32,
                                         int& out_port);
}

// 模块 ROOT 环境切换（实现在别的成员里，本 TU 只引用）。
namespace default_modules::module_root {
KModErr set_current_root(bool current);
}


// CpuPinGuardAuto：把当前线程临时绑到 sched_getcpu() 返回的 CPU 上，
// 析构时把 sched_getaffinity() 抓到的原始掩码写回。全局命名空间。
//
// 都在下面逐条还原。
class CpuPinGuardAuto {
public:
    explicit CpuPinGuardAuto(bool verbose) {
        m_pinned = false;
        m_pinned_cpu = -1;
        m_verbose = verbose;
        CPU_ZERO(&m_original_affinity);
        if (sched_getaffinity(0, sizeof(cpu_set_t), &m_original_affinity) != 0) {
            if (m_verbose) {
                std::fprintf(stderr, "CpuPinGuardAuto: sched_getaffinity failed: %s\n",
                             std::strerror(errno));
            }
            return;
        }
        const int cpu = sched_getcpu();
        if (cpu < 0) {
            if (m_verbose) {
                std::fprintf(stderr, "CpuPinGuardAuto: sched_getcpu failed: %s\n",
                             std::strerror(errno));
            }
            return;
        }
        m_pinned_cpu = cpu;
        cpu_set_t target_set;
        CPU_ZERO(&target_set);
        CPU_SET(cpu, &target_set);
        if (sched_setaffinity(0, sizeof(cpu_set_t), &target_set) != 0) {
            if (m_verbose) {
                std::fprintf(stderr,
                             "CpuPinGuardAuto: sched_setaffinity(cpu %d) failed: %s\n",
                             cpu, std::strerror(errno));
            }
            return;
        }
        std::atomic_thread_fence(std::memory_order_seq_cst);
        m_pinned = true;
        if (m_verbose) {
            std::printf("CpuPinGuardAuto: pinned to CPU %d\n", m_pinned_cpu);
        }
    }

    ~CpuPinGuardAuto() {
        if (!m_pinned) {
            return;
        }
        if (sched_setaffinity(0, sizeof(cpu_set_t), &m_original_affinity) != 0) {
            if (m_verbose) {
                std::fprintf(stderr,
                             "CpuPinGuardAuto: restore sched_setaffinity failed: %s\n",
                             std::strerror(errno));
            }
        }
        std::atomic_thread_fence(std::memory_order_seq_cst);
        if (m_verbose) {
            std::printf("CpuPinGuardAuto: restored from CPU %d\n", m_pinned_cpu);
        }
    }

    CpuPinGuardAuto(const CpuPinGuardAuto&) = delete;
    CpuPinGuardAuto& operator=(const CpuPinGuardAuto&) = delete;
    CpuPinGuardAuto(CpuPinGuardAuto&&) = delete;
    CpuPinGuardAuto& operator=(CpuPinGuardAuto&&) = delete;

private:
    cpu_set_t m_original_affinity;  // offset 0   (128 B)
    bool m_pinned;                  // offset 128 是否成功绑过核
    int m_pinned_cpu;               // offset 132 绑到的 CPU / 恢复时用于打印
    bool m_verbose;                 // offset 136
};

static_assert(sizeof(CpuPinGuardAuto) == 144 && alignof(CpuPinGuardAuto) == 8,
              "layout constraint violated");

namespace maplesu::compat {
using maplesu::sdk::fork_base_info;
using maplesu::sdk::fork_pipe_info;
using maplesu::sdk::fork_pipe_child_process;

static inline KModErr set_webui_root(bool current) {
    return default_modules::module_root::set_current_root(current);
}

static inline bool webui_log_enabled() {
    return maplesu::sdk::is_maplesu_log_enabled();
}
}  // namespace maplesu::compat
