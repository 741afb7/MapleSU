// module root implementation.
//
#define SymbolLookupSilentGuard_8dfccc5cf454087c7314725d3487e703 \
    SymbolLookupSilentGuard_c67c0e4fed21dd11b34c67e0a5b878c8

#include "kernel_module_kit_umbrella.h"
#include "maplesu_request_private.h"
#include "maplesu_early_fixed_private.h"
#include "maplesu_storage_private.h"
#include "maplesu_root_private.h"
#include "maplesu_caps_private.h"
#include "maplesu_hook_private.h"
#include "maplesu_request_identity.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <new>
#include <cstdio>
#include <string>
#include <vector>

#include <fcntl.h>
#include <signal.h>       // signal / SIGPIPE / SIG_IGN
#include <sys/stat.h>
#include <sys/prctl.h>    // prctl + linux/prctl.h 的 PR_GET_SECCOMP/PR_GET_SECUREBITS
#include <unistd.h>

#include "cgroup_v1_tasks_self.h"
#include "cgroup_v2_self.h"
#include "selinux_procattr_util.h"


// CLONE_NEWNS：bionic <sched.h> 不导出该宏（linux/sched.h 才是 0x00020000），
static constexpr int kCloneNewNs = 0x00020000;

// 8 个 0xF0 字节的"非法进程名"常量在 set_current_name_bypass_selinux() 内部，
// mov x8,#-0xf0f0f0f0f0f0f10 完全一致。

namespace kernel_module {
// Sole consumer: resolve the complete legacy Root credential layout together.
KModErr maplesu_resolve_root_credential_offsets(uint32_t& task_cred, uint32_t& cred_uid,
    uint32_t& cred_euid, uint32_t& task_seccomp, uint32_t& cred_inheritable);
KModErr get_filldir64_addr(uint64_t& out_filldir64_addr);
}  // namespace kernel_module

struct KernelSymbolOffset {
    uint64_t avc_denied_ret_addr;   // [0] get_avc_denied_kaddr 的 ret_after_can_hook_kaddr
    uint64_t filldir64_addr;        // [1] get_filldir64_addr 输出
    uint64_t compat_filldir_addr;   // [2] kallsyms "compat_filldir" (Prefix) 命中地址
    uint64_t hbt_filldir64_addr;    // [3] kallsyms "hbt:filldir64"（MTK HBT，可缺）
    uint64_t iterate_dir_addr;      // [4] kallsyms "iterate_dir"
    uint64_t audit_log_start_addr;  // [5] kallsyms "audit_log_start"
};
static_assert(sizeof(KernelSymbolOffset) == 48, "KernelSymbolOffset 布局实测：6 × u64 = 48");

namespace default_modules {
namespace module_root {

namespace {
struct RootTransitionState {
    pid_t process = 0;
    RootTransitionResult result{};
};
thread_local RootTransitionState root_transition;

struct GeneratedActorStaging {
    kernel_module::RequestIdentity identity;
    kernel_module::OwnedHookActor actor{};
    bool transferred = false;
    bool boot_retained = false;
    ~GeneratedActorStaging() noexcept {
        if (!actor.allocation || transferred || boot_retained) return;
        const kernel_module::HookCleanupReceiptScope preserve_receipt;
        if (!identity.matches()) return;
        try {
            const auto reply = kernel_module::maplesu_hook_reply();
            if (!reply.completed || reply.status == MAPLESU_REQUEST_STATUS_DENIED ||
                reply.status == MAPLESU_REQUEST_STATUS_STALE ||
                (reply.effects & MAPLESU_EFFECT_SESSION_CLOSED)) {
                (void)kernel_module::maplesu_hook_close();
            } else if (kernel_module::maplesu_hook_release(actor.allocation) != KModErr::OK) {
                std::fprintf(stderr, "MapleSU generated actor release failed\n");
                (void)kernel_module::maplesu_hook_close();
            }
        } catch (...) {
            try { (void)kernel_module::maplesu_hook_close(); } catch (...) {}
        }
    }
};
}

RootTransitionResult current_root_transition() {
    return root_transition.process == getpid() ? root_transition.result : RootTransitionResult{};
}

// patch 族声明（定义在 idx 71..78 成员里；本 TU 只调用）
//
//   PatchBase            = { i32, i32, i32, i32 }                -> 16 B / align 4
//   PatchCurrent         = { PatchBase }                          -> 16 B / align 4
//   PatchAvcDenied       = { PatchBase, i64 }                     -> 24 B / align 8
//   PatchFilldir64       = { PatchBase, i64 }                     -> 24 B / align 8
//   PatchCompatFilldir   = { PatchBase, i64 }                     -> 24 B / align 8
//   PatchAuditLogStart   = { PatchBase, i64 }                     -> 24 B / align 8
//   PatchMtkHbtFilldir64 = { PatchBase, i64 }                     -> 24 B / align 8
//   PatchIterateDir      = { PatchBase, i64 }                     -> 24 B / align 8
class PatchBase {
public:
    // 4 个 u32 由 set_current_root/set_current_caps 从偏移解析结果直接传入
    PatchBase(uint32_t cred_offset, uint32_t cred_atomic_usage_size,
              uint32_t cred_securebits_offset, uint32_t cred_cap_effective_offset);
    ~PatchBase();

private:
    uint32_t m_cred_offset_;
    uint32_t m_cred_atomic_usage_size_;
    uint32_t m_cred_securebits_offset_;
    uint32_t m_cred_cap_effective_offset_;
};
static_assert(sizeof(PatchBase) == 16, "PatchBase 布局实测：4 × u32 = 16");
static_assert(alignof(PatchBase) == 4, "PatchBase align 实测 = 4");

class PatchCurrent : public PatchBase {
public:
    explicit PatchCurrent(const PatchBase& base);
    ~PatchCurrent();

    KModErr set_current_root(uint32_t task_field_offset);
    KModErr set_current_caps(const kernel_module::caps_info& caps);
};
static_assert(sizeof(PatchCurrent) == 16, "PatchCurrent 布局实测：只有基类 = 16");
static_assert(alignof(PatchCurrent) == 4, "PatchCurrent align 实测 = 4");

class PatchAvcDenied : public PatchBase {
public:
    PatchAvcDenied(const PatchBase& base, const uint64_t& avc_denied_addr);
    ~PatchAvcDenied();

    KModErr patch_avc_denied();

private:
    uint64_t m_avc_denied_addr_;
};
static_assert(sizeof(PatchAvcDenied) == 24, "PatchAvcDenied 布局实测：PatchBase(16) + u64");
static_assert(alignof(PatchAvcDenied) == 8, "PatchAvcDenied align 实测 = 8");

class PatchFilldir64 : public PatchBase {
public:
    PatchFilldir64(const PatchBase& base, uint64_t filldir64_addr);
    ~PatchFilldir64();

    KModErr patch_filldir64(const std::vector<std::string>& hide_names);

private:
    uint64_t m_filldir64_addr_;
};
static_assert(sizeof(PatchFilldir64) == 24, "PatchFilldir64 布局实测：PatchBase(16) + u64");
static_assert(alignof(PatchFilldir64) == 8, "PatchFilldir64 align 实测 = 8");

class PatchCompatFilldir : public PatchBase {
public:
    PatchCompatFilldir(const PatchBase& base, uint64_t compat_filldir_addr);
    ~PatchCompatFilldir();

    KModErr patch_compat_filldir(const std::vector<std::string>& hide_names);

private:
    uint64_t m_compat_filldir_addr_;
};
static_assert(sizeof(PatchCompatFilldir) == 24, "PatchCompatFilldir 布局实测：PatchBase(16) + u64");
static_assert(alignof(PatchCompatFilldir) == 8, "PatchCompatFilldir align 实测 = 8");

class PatchAuditLogStart : public PatchBase {
public:
    PatchAuditLogStart(const PatchBase& base, const uint64_t& audit_log_start_addr);
    ~PatchAuditLogStart();

    KModErr patch_audit_log_start();

private:
    uint64_t m_audit_log_start_addr_;
};
static_assert(sizeof(PatchAuditLogStart) == 24, "PatchAuditLogStart 布局实测：PatchBase(16) + u64");
static_assert(alignof(PatchAuditLogStart) == 8, "PatchAuditLogStart align 实测 = 8");

class PatchMtkHbtFilldir64 : public PatchBase {
public:
    PatchMtkHbtFilldir64(const PatchBase& base, uint64_t hbt_filldir64_addr);
    ~PatchMtkHbtFilldir64();

    KModErr generate_hook_fake_filldir64(const std::vector<std::string>& hide_names,
                                        uint64_t& out_func_kaddr);
    KModErr generate_hook_fake_filldir64_owned(const std::vector<std::string>& hide_names,
        uint64_t& out_func_kaddr, kernel_module::OwnedHookActor& actor);

private:
    uint64_t m_hbt_filldir64_addr_;
};
static_assert(sizeof(PatchMtkHbtFilldir64) == 24,
              "PatchMtkHbtFilldir64 布局实测：PatchBase(16) + u64");
static_assert(alignof(PatchMtkHbtFilldir64) == 8, "PatchMtkHbtFilldir64 align 实测 = 8");

class PatchIterateDir : public PatchBase {
public:
    PatchIterateDir(const PatchBase& base, uint64_t iterate_dir_addr);
    ~PatchIterateDir();

    KModErr patch_iterate_dir(uint64_t original_filldir64, uint64_t target_filldir64);
    KModErr patch_iterate_dir_owned(uint64_t original_filldir64, uint64_t target_filldir64,
        const kernel_module::OwnedHookActor& actor, bool& transferred);

private:
    uint64_t m_iterate_dir_addr_;
};
static_assert(sizeof(PatchIterateDir) == 24, "PatchIterateDir 布局实测：PatchBase(16) + u64");
static_assert(alignof(PatchIterateDir) == 8, "PatchIterateDir align 实测 = 8");

// 内部函数：把当前进程名换成 8 个 0xF0 字节的非法名字，用于绕过 SELinux 的
// @__const._ZN15default_modules11module_rootL31set_current_name_bypass_selinuxEv.name。
// 这里保持 `static`（内部链接，mangling 带 L）与同样的函数名/Basename 形态。
static KModErr set_current_name_bypass_selinux() {
    const char name[] = "\xF0\xF0\xF0\xF0\xF0\xF0\xF0\xF0";
    return kernel_module::set_current_process_name(name);
}

// T default_modules::module_root::set_current_root(bool bypass_selinux)
//
//   偏移解析 → PatchCurrent::set_current_root → 假进程名（可选）→
//   setsid/setpgid → SIGPIPE 忽略 → 进入 /proc/1/ns/mnt 命名空间并 unshare →
//   cgroup 根迁移（v2 + v1 全线程）→ SELinux ctx 复位 → OK。
static KModErr set_current_root_impl(bool bypass_selinux, bool preserve_session) {
    root_transition = {};
    root_transition.process = getpid();
    auto& result = root_transition.result;
    result.phase = "credentials";
    result.owner_mode = kernel_module::maplesu_request_owner_mode();
    const auto finish = [&](KModErr error, int system_error = 0) {
        result.error = error;
        result.system_error = system_error;
        return error;
    };
    try {
        bool managed = false;
        uint64_t capabilities = 0;
        KModErr err = kernel_module::maplesu_request_credential_channel(managed, capabilities);
        result.credentials_managed = managed;
        if (is_failed(err)) {
            result.credentials_error = err;
            return finish(err, errno);
        }
        if (managed) {
            result.phase = "task_state";
            const uint64_t required = MAPLESU_CAP_CREDENTIAL | MAPLESU_CAP_TASK_STATE | MAPLESU_CAP_PROCATTR;
            if ((capabilities & required) != required) {
                result.task_state_error = KModErr::ERR_MODULE_TEST_CHANNEL;
                errno = EOPNOTSUPP;
                return finish(result.task_state_error, errno);
            }
            result.phase = "credentials";
            result.credentials_attempted = true;
            errno = 0;
            err = kernel_module::maplesu_request_owner_root();
            const int credentials_errno = errno;
            result.credentials_error = err;
            result.credentials = kernel_module::maplesu_request_management_reply();
            result.credentials_committed = result.credentials.completed &&
                result.credentials.command == MAPLESU_REQUEST_CRED_ROOT &&
                (result.credentials.effects & MAPLESU_EFFECT_COMMITTED);
            if (is_failed(err)) {
                errno = credentials_errno;
                return finish(err, credentials_errno);
            }

            result.phase = "task_state";
            result.task_state_attempted = true;
            errno = 0;
            err = kernel_module::maplesu_request_owner_task_state();
            result.task_state_error = err;
            result.task_state = kernel_module::maplesu_request_management_reply();
            if (is_failed(err)) return finish(err, errno);
        } else {
            uint32_t cred_offset = 0;
            uint32_t uid_offset = 0;
            uint32_t euid_offset = 0;
            uint32_t seccomp_offset = 0;
            uint32_t cap_inheritable_offset = 0;

            err = kernel_module::maplesu_resolve_root_credential_offsets(cred_offset, uid_offset,
                euid_offset, seccomp_offset, cap_inheritable_offset);
            if (is_failed(err)) { result.credentials_error = err; return finish(err); }

            PatchBase patch_base(cred_offset, uid_offset, euid_offset, cap_inheritable_offset);
            PatchCurrent patch_current(patch_base);
            result.credentials_attempted = true;
            err = patch_current.set_current_root(seccomp_offset);
            result.credentials_error = err;
            if (is_failed(err)) return finish(err);
        }

        if (bypass_selinux && !managed) {
            result.phase = "process_name";
            err = set_current_name_bypass_selinux();
            if (is_failed(err)) return finish(err, errno);
        }

        result.phase = "session";
        if (!preserve_session && setsid() < 0 && getpgrp() != getpid() && setpgid(0, 0) < 0)
            return finish(KModErr::ERR_MODULE_CRED_DANGER, errno);
        result.phase = "signal";
        if (!preserve_session && signal(SIGPIPE, SIG_IGN) == SIG_ERR)
            return finish(KModErr::ERR_MODULE_CRED_DANGER, errno);

        result.phase = "namespace_open";
        const int mnt_ns_fd = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
        if (mnt_ns_fd < 0) return finish(KModErr::ERR_MODULE_OPEN_FILE, errno);
        int self_ns_fd = open("/proc/thread-self/ns/mnt", O_RDONLY | O_CLOEXEC);
        if (self_ns_fd < 0 && errno == ENOENT) {
            char path[96];
            std::snprintf(path, sizeof(path), "/proc/self/task/%d/ns/mnt",
                static_cast<int>(maplesu::root_environment::selinux_procattr::detail::gettid_compat()));
            self_ns_fd = open(path, O_RDONLY | O_CLOEXEC);
        }
        if (self_ns_fd < 0) {
            const int saved = errno;
            close(mnt_ns_fd);
            return finish(KModErr::ERR_MODULE_OPEN_FILE, saved);
        }
        result.phase = "namespace_stat";
        struct stat target_ns{}, self_ns{};
        const int stat_error = fstat(mnt_ns_fd, &target_ns) < 0 || fstat(self_ns_fd, &self_ns) < 0 ? errno : 0;
        close(self_ns_fd);
        if (stat_error) {
            close(mnt_ns_fd);
            return finish(KModErr::ERR_MODULE_READ_FILE, stat_error);
        }
        result.phase = "namespace_join";
        const bool same_namespace = target_ns.st_dev == self_ns.st_dev && target_ns.st_ino == self_ns.st_ino;
        const int join_error = !same_namespace && setns(mnt_ns_fd, kCloneNewNs) < 0 ? errno : 0;
        close(mnt_ns_fd);
        if (join_error) return finish(KModErr::ERR_MODULE_CRED_DANGER, join_error);
        result.phase = "namespace_private";
        if (unshare(kCloneNewNs) < 0) return finish(KModErr::ERR_MODULE_CRED_DANGER, errno);

        result.phase = "cgroup_v2";
        const auto v2 = maplesu::root_environment::cg_v2::migrate_self_to_root();
        if (!v2.ok)
            return finish(KModErr::ERR_MODULE_WRITE_FILE, v2.err ? v2.err : EIO);
        result.phase = "cgroup_v1";
        const auto v1 = maplesu::root_environment::cg_v1::migrate_self_threads_v1(nullptr, nullptr);
        result.threads_total = v1.threads_total;
        result.cpuset_moved = v1.cpuset_moved;
        result.stune_moved = v1.stune_moved;
        result.cgroup_error = v1.err_any;
        if (!v1.ok()) return finish(KModErr::ERR_MODULE_WRITE_FILE, v1.err_any ? v1.err_any : EIO);

        // Avoid replacing credentials when the requested context is already set.
        const char shell_context[] = "u:r:shell:s0";
        result.phase = "selinux_current";
        if (maplesu::root_environment::selinux_procattr::getcon() != shell_context) {
            if (managed) {
                err = kernel_module::maplesu_request_owner_procattr(MAPLESU_PROCATTR_CURRENT,
                    shell_context, sizeof(shell_context));
                if (is_failed(err)) return finish(KModErr::ERR_SET_FILE_SELINUX, errno);
            } else if (maplesu::root_environment::selinux_procattr::setcon(shell_context) < 0)
                return finish(KModErr::ERR_SET_FILE_SELINUX, errno);
        }
        result.phase = "selinux_exec";
        if (maplesu::root_environment::selinux_procattr::getexeccon() != shell_context) {
            if (managed) {
                err = kernel_module::maplesu_request_owner_procattr(MAPLESU_PROCATTR_EXEC,
                    shell_context, sizeof(shell_context));
                if (is_failed(err)) return finish(KModErr::ERR_SET_FILE_SELINUX, errno);
            } else if (maplesu::root_environment::selinux_procattr::setexeccon(shell_context) < 0)
                return finish(KModErr::ERR_SET_FILE_SELINUX, errno);
        }
        result.phase = "complete";
        result.environment_completed = true;
        return finish(KModErr::OK);
    } catch (const std::bad_alloc&) {
        if (std::strcmp(result.phase, "credentials") == 0) result.credentials_error = KModErr::ERR_NO_MEM;
        if (std::strcmp(result.phase, "task_state") == 0) result.task_state_error = KModErr::ERR_NO_MEM;
        return finish(KModErr::ERR_NO_MEM);
    }
}

KModErr set_current_root(bool bypass_selinux) {
    return set_current_root_impl(bypass_selinux, false);
}


// T default_modules::module_root::set_current_caps(const kernel_module::caps_info& caps)
// 取 4 个偏移 → PatchBase + PatchCurrent → PatchCurrent::set_current_caps(caps)。
KModErr set_current_caps(const kernel_module::caps_info& caps) {
    bool managed = false;
    uint64_t capabilities = 0;
    const auto channel_error = kernel_module::maplesu_request_credential_channel(managed, capabilities);
    if (is_failed(channel_error)) return channel_error;
    if (managed) {
        const maplesu_caps_v3 value{caps.inheritable,caps.permitted,caps.effective,caps.bounding,caps.ambient};
        return kernel_module::maplesu_request_owner_caps(value);
    }
    uint32_t cred_offset = 0;
    uint32_t uid_offset = 0;
    uint32_t euid_offset = 0;
    uint32_t cap_inheritable_offset = 0;

    KModErr err = kernel_module::get_task_struct_cred_offset(cred_offset);
    if (is_failed(err)) return err;
    err = kernel_module::get_cred_uid_offset(uid_offset);
    if (is_failed(err)) return err;
    err = kernel_module::get_cred_euid_offset(euid_offset);
    if (is_failed(err)) return err;
    err = kernel_module::get_cred_cap_inheritable_offset(cap_inheritable_offset);
    if (is_failed(err)) return err;

    PatchBase patch_base(cred_offset, uid_offset, euid_offset, cap_inheritable_offset);
    PatchCurrent patch_current(patch_base);
    return patch_current.set_current_caps(caps);
}

// T default_modules::module_root::init_anti_detect_root_env(
//       const std::vector<std::string>& hide_names, bool only_print)
//
// 两阶段：
//   A. 提权后凭据安全预检（读取错误原样返回，状态不符返回 ERR_MODULE_CRED_DANGER）；
//   B. 逐项安装反检测 patch：avc_denied / filldir64 / compat_filldir /
//      (MTK hbt filldir64 + iterate_dir) / audit_log_start。
// only_print 为真时只做到 B 的地址解析与打印，不构造任何 patch 对象。
KModErr init_anti_detect_root_env(const std::vector<std::string>& hide_names, bool only_print) {
    KModErr err = set_current_root(true);
    if (kernel_module::maplesu_request_helper_mode()) {
        if (is_failed(err)) return err;
        maplesu::storage::StorageContext storage;
        RETURN_IF_ERROR(maplesu::storage::current_context(storage));
        if (!only_print && (hide_names.size() != 2 || hide_names[0] != storage.locator.kernel_component ||
            hide_names[1] != storage.locator.system_component)) {
            errno = EINVAL;
            return KModErr::ERR_MODULE_PARAM;
        }
        kernel_module::RequestEarlyFixedResult fixed{};
        return kernel_module::maplesu_request_helper_fixed_business(
            MAPLESU_EARLY_FIXED_ROOT_ENV, 0, 0, only_print ? 1 : 0, fixed);
    }

    do {
        if (is_failed(err)) break;
        const uid_t uid = getuid();
        kernel_module::caps_info caps{};
        bool ambient_present = false;
        err = kernel_module::get_current_caps_snapshot(caps, ambient_present);
        if (is_failed(err)) break;
        const uint64_t cap_ability_max = kernel_module::get_cap_ability_max();
        if (cap_ability_max == 0) {
            err = KModErr::ERR_MODULE_CAP_ABILITY;
            break;
        }
        const int seccomp_mode = prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
        const int securebits = prctl(PR_GET_SECUREBITS);

        printf("capInh: 0x%lx\n", static_cast<unsigned long>(caps.inheritable));
        printf("capPrm: 0x%lx\n", static_cast<unsigned long>(caps.permitted));
        printf("capEff: 0x%lx\n", static_cast<unsigned long>(caps.effective));
        printf("capBnd: 0x%lx\n", static_cast<unsigned long>(caps.bounding));
        printf("capAmb: 0x%lx\n", static_cast<unsigned long>(caps.ambient));
        printf("seccomp: %d\n", seccomp_mode);
        printf("securebits: %d\n", securebits);

        //   uid == 0 且 inheritable/permitted/effective/bounding 全部等于能力上限；
        //   按同一次快照是否含 ambient 决定是否校验第五段能力集；
        //   seccomp 与 securebits 都必须为 0（否则说明还带着限制）。
        bool cred_safe = (uid == 0)
                      && (caps.inheritable == cap_ability_max)
                      && (caps.permitted == cap_ability_max)
                      && (caps.effective == cap_ability_max)
                      && (caps.bounding == cap_ability_max);
        if (cred_safe) {
            if (ambient_present) {
                cred_safe = (caps.ambient == cap_ability_max)
                         && (seccomp_mode == 0)
                         && (securebits == 0);
            } else {
                cred_safe = (seccomp_mode == 0) && (securebits == 0);
            }
        }
        err = cred_safe ? KModErr::OK : KModErr::ERR_MODULE_CRED_DANGER;
    } while (false);

    printf("precheck_cred_safety: %s\n", to_string(err).c_str());

    if (is_ok(err)) {
        // B 阶段放在 do/while(false) 里：任一失败 break 到统一收尾，
        do {
            uint32_t cred_offset = 0;
            uint32_t uid_offset = 0;
            uint32_t euid_offset = 0;
            uint32_t seccomp_offset = 0;
            uint32_t cap_inheritable_offset = 0;

            err = kernel_module::get_task_struct_cred_offset(cred_offset);
            if (is_failed(err)) break;
            printf("cred offset: 0x%x\n", cred_offset);

            err = kernel_module::get_cred_uid_offset(uid_offset);
            if (is_failed(err)) break;
            err = kernel_module::get_cred_euid_offset(euid_offset);
            if (is_failed(err)) break;
            printf("cred uid offset: 0x%x\n", uid_offset);

            err = kernel_module::get_task_struct_seccomp_offset(seccomp_offset);
            if (is_failed(err)) break;
            printf("seccomp offset: 0x%x\n", seccomp_offset);

            err = kernel_module::get_cred_cap_inheritable_offset(cap_inheritable_offset);
            if (is_failed(err)) break;
            printf("cap_inheritable offset: 0x%x\n", cap_inheritable_offset);

            KernelSymbolOffset ks{};
            uint64_t avc_denied_kaddr = 0;

            err = kernel_module::get_avc_denied_kaddr(avc_denied_kaddr, ks.avc_denied_ret_addr);
            if (is_failed(err)) break;
            printf("avc_denied entry addr: %p, ret addr: %p\n",
                   reinterpret_cast<void*>(avc_denied_kaddr),
                   reinterpret_cast<void*>(ks.avc_denied_ret_addr));

            err = kernel_module::get_filldir64_addr(ks.filldir64_addr);
            if (is_failed(err)) break;
            printf("filldir64 addr: %p\n", reinterpret_cast<void*>(ks.filldir64_addr));

            // compat_filldir 必须存在（否则说明内核版本不匹配），用 Prefix 匹配
            kernel_module::SymbolHit compat_filldir_hit{};
            err = kernel_module::kallsyms_lookup_name(
                "compat_filldir", compat_filldir_hit, kernel_module::SymbolMatchMode::Prefix);
            if (is_failed(err)) break;
            printf("%s, addr: %p\n", compat_filldir_hit.name,
                   reinterpret_cast<void*>(compat_filldir_hit.addr));
            ks.compat_filldir_addr = compat_filldir_hit.addr;

            // hbt:filldir64 与 iterate_dir 在非 MTK 内核上可能不存在：
            // 但要把符号查找期间的 printf 静音，避免刷屏。
            int lookup_errno = 0;
            {
                SymbolLookupSilentGuard_c67c0e4fed21dd11b34c67e0a5b878c8 silent_guard;
                err = kernel_module::kallsyms_lookup_name("hbt:filldir64", ks.hbt_filldir64_addr);
                lookup_errno = errno;
                if (err == KModErr::OK || err == KModErr::ERR_MODULE_SYMBOL_NOT_EXIST) {
                    printf("hbt_filldir64, addr: %p\n", reinterpret_cast<void*>(ks.hbt_filldir64_addr));
                    err = kernel_module::kallsyms_lookup_name("iterate_dir", ks.iterate_dir_addr);
                    lookup_errno = errno;
                    if (err == KModErr::OK || err == KModErr::ERR_MODULE_SYMBOL_NOT_EXIST)
                        printf("iterate_dir, addr: %p\n", reinterpret_cast<void*>(ks.iterate_dir_addr));
                }
            }
            if (err != KModErr::OK && err != KModErr::ERR_MODULE_SYMBOL_NOT_EXIST) {
                errno = lookup_errno;
                break;
            }

            err = kernel_module::kallsyms_lookup_name("audit_log_start", ks.audit_log_start_addr);
            if (is_failed(err)) break;
            printf("audit_log_start kaddr: %p\n",
                   reinterpret_cast<void*>(ks.audit_log_start_addr));

            if (only_print) {
                err = KModErr::OK;
                break;
            }

            PatchBase patch_base(cred_offset, uid_offset, euid_offset, cap_inheritable_offset);
            PatchAvcDenied patch_avc_denied(patch_base, ks.avc_denied_ret_addr);
            PatchFilldir64 patch_filldir64(patch_base, ks.filldir64_addr);
            PatchCompatFilldir patch_compat_filldir(patch_base, ks.compat_filldir_addr);
            PatchAuditLogStart patch_audit_log_start(patch_base, ks.audit_log_start_addr);

            err = patch_avc_denied.patch_avc_denied();
            printf("patch avc_denied: %s\n", to_string(err).c_str());
            if (is_failed(err)) break;

            err = patch_filldir64.patch_filldir64(hide_names);
            printf("patch filldir64: %s\n", to_string(err).c_str());
            if (is_failed(err)) break;

            err = patch_compat_filldir.patch_compat_filldir(hide_names);
            printf("patch compat_filldir: %s\n", to_string(err).c_str());
            if (is_failed(err)) break;

            // ---- B5) MTK HBT 专用：把 hbt:filldir64 隐藏成"假 filldir64"，
            //      再把 iterate_dir 的 actor 从原 filldir64 改指向假函数。
            if (ks.hbt_filldir64_addr != 0 && ks.iterate_dir_addr != 0) {
                PatchMtkHbtFilldir64 patch_mtk_hbt_filldir64(patch_base, ks.hbt_filldir64_addr);
                PatchIterateDir patch_iterate_dir(patch_base, ks.iterate_dir_addr);
                uint64_t fake_filldir64_kaddr = 0;
                GeneratedActorStaging staging;
                if (!staging.identity.capture()) {
                    err = KModErr::ERR_MODULE_ROOT_KEY;
                    break;
                }
                err = kernel_module::maplesu_hook_legacy_boot_selected(staging.boot_retained);
                if (is_failed(err)) break;
                err = patch_mtk_hbt_filldir64.generate_hook_fake_filldir64_owned(
                    hide_names, fake_filldir64_kaddr, staging.actor);
                if (is_failed(err)) break;
                if (!staging.identity.matches()) {
                    err = KModErr::ERR_MODULE_ROOT_KEY;
                    break;
                }
                err = patch_iterate_dir.patch_iterate_dir_owned(ks.hbt_filldir64_addr,
                    fake_filldir64_kaddr, staging.actor, staging.transferred);
                printf("patch iterate_dir: %s\n", to_string(err).c_str());
                if (is_failed(err)) break;
            }

            err = patch_audit_log_start.patch_audit_log_start();
            printf("patch audit_log_start: %s\n", to_string(err).c_str());
        } while (false);

        const int saved_errno = errno;
        printf("patch_kernel_handler: %s\n", to_string(err).c_str());
        errno = saved_errno;
    }

    return err;
}

// T default_modules::module_root::get_module_eng_name()
const char* get_module_eng_name() {
    return "module_root";
}

}  // namespace module_root
}  // namespace default_modules
