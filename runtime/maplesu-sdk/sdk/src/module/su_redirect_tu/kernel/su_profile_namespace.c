// SPDX-License-Identifier: GPL-3.0-only
#include <linux/errno.h>
#include <linux/string.h>
#include <linux/version.h>
#include "su_profile_namespace.h"
#include "su_install_registry.h"

#ifndef MSU_SU_NAMESPACE_NATIVE_VERIFIED
#define MSU_SU_NAMESPACE_NATIVE_VERIFIED 0
#endif

/* Source envelope only; each vendor target still requires P2 qualification. */
#if defined(CONFIG_ARM64) && defined(CONFIG_NAMESPACES) && \
    LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0) && \
    LINUX_VERSION_CODE < KERNEL_VERSION(6, 19, 0)
#define MSU_SU_NAMESPACE_NATIVE_SOURCE 1
#include <linux/atomic.h>
#include <linux/cred.h>
#include <linux/dcache.h>
#include <linux/fdtable.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/fs_struct.h>
#include "manager_dynamic_layout.h"
#include <linux/init_task.h>
#include <linux/irqflags.h>
#include <linux/limits.h>
#include <linux/preempt.h>
#include <linux/namei.h>
#include <linux/nsproxy.h>
#include <linux/pid.h>
#include <linux/pid_namespace.h>
#include <linux/proc_ns.h>
#include <linux/rcupdate.h>
#include <linux/sched/task.h>
#include <linux/slab.h>
#include <linux/stddef.h>
#include <linux/syscalls.h>
#include <asm/ptrace.h>
#include <uapi/linux/mount.h>

/* ARM64 SYSCALL_DEFINE wrappers, not long(int, int) function pointers.
 * For 5.10--6.18 regs[0..5] are the native arguments. No userspace pointers,
 * compat dispatch, syscall table lookup or set_fs/address-limit change. */
asmlinkage long __arm64_sys_setns(const struct pt_regs *regs);
asmlinkage long __arm64_sys_unshare(const struct pt_regs *regs);
asmlinkage long __arm64_sys_close(const struct pt_regs *regs);
#ifndef MSU_PATH_MOUNT_PATH_QUALIFIER
#error path_mount target type selection required
#endif
extern int path_mount(const char *dev_name, MSU_PATH_MOUNT_PATH_QUALIFIER struct path *path,
    const char *type_page, unsigned long flags, void *data_page);

static_assert(__builtin_types_compatible_p(typeof(&__arm64_sys_setns),
    long (*)(const struct pt_regs *)));
static_assert(__builtin_types_compatible_p(typeof(&__arm64_sys_unshare),
    long (*)(const struct pt_regs *)));
static_assert(__builtin_types_compatible_p(typeof(&__arm64_sys_close),
    long (*)(const struct pt_regs *)));

/* New private collector section; do not reuse CORE's existing layout words. */
const unsigned long msu_su_namespace_native_layout[] __used
__section(".msu.su_namespace.layout") = {
    1, sizeof(struct msu_su_namespace_receipt),
    offsetof(struct msu_su_namespace_receipt, mode),
    offsetof(struct msu_su_namespace_receipt, effects),
    offsetof(struct msu_su_namespace_receipt, error),
    offsetof(struct msu_su_namespace_receipt, operation_error),
    offsetof(struct msu_su_namespace_receipt, close_error),
    offsetof(struct msu_su_namespace_receipt, cwd_error),
    offsetof(struct msu_su_namespace_receipt, validation_error),
    sizeof(struct pt_regs), offsetof(struct pt_regs, regs),
    sizeof(((struct pt_regs *)0)->regs[0]),
    offsetof(struct pt_regs, sp), offsetof(struct pt_regs, pstate),
    offsetof(struct task_struct, fs), offsetof(struct task_struct, files),
    offsetof(struct task_struct, nsproxy), offsetof(struct fs_struct, root),
    offsetof(struct fs_struct, pwd), sizeof(struct path),
    offsetof(struct path, mnt), offsetof(struct path, dentry),
    offsetof(struct nsproxy, mnt_ns), offsetof(struct files_struct, count),
    sizeof(init_cred), sizeof(init_pid_ns), sizeof(struct proc_ns_operations),
    sizeof(struct nsproxy), sizeof(struct fs_struct), sizeof(struct files_struct),
    LINUX_VERSION_CODE
};

static int msu_su_namespace_global(struct msu_su_namespace_receipt *receipt)
{
    struct path saved_pwd, ns_path, new_pwd;
    struct task_struct *pid1;
    struct pid *pid;
    struct file *file;
    struct pt_regs regs = {0};
    char *buffer, *absolute;
    int fd, error;

    buffer = kmalloc(PATH_MAX, GFP_KERNEL);
    if (!buffer) { receipt->operation_error = -ENOMEM; return -ENOMEM; }
    msu_dynamic_get_fs_pwd(MSU_DYN_FIELD(current, task_struct, fs), &saved_pwd);
    absolute = d_path(&saved_pwd, buffer, PATH_MAX);
    path_put(&saved_pwd);
    if (IS_ERR(absolute)) {
        error = PTR_ERR(absolute);
        goto out;
    }
    if (absolute[0] != '/') {
        error = -ENOENT;
        goto out;
    }
    rcu_read_lock();
    pid = find_pid_ns(1, &init_pid_ns);
    pid1 = pid ? get_pid_task(pid, PIDTYPE_PID) : NULL;
    rcu_read_unlock();
    if (!pid1) {
        error = -ESRCH;
        goto out;
    }
    error = ns_get_path(&ns_path, pid1, &mntns_operations);
    msu_dynamic_put_task_struct(pid1);
    if (error) goto out;
    file = dentry_open(&ns_path, O_RDONLY, &init_cred);
    path_put(&ns_path);
    if (IS_ERR(file)) {
        error = PTR_ERR(file);
        goto out;
    }
    fd = get_unused_fd_flags(O_CLOEXEC);
    if (fd < 0) {
        error = fd;
        fput(file);
        goto out;
    }
    fd_install(fd, file);
    regs.regs[0] = (unsigned int)fd;
    regs.regs[1] = CLONE_NEWNS;
    error = (int)__arm64_sys_setns(&regs);
    receipt->operation_error = error;
    if (!error) receipt->effects |= MSU_SU_NS_APPLIED;
    memset(&regs, 0, sizeof(regs));
    regs.regs[0] = (unsigned int)fd;
    receipt->close_error = (int)__arm64_sys_close(&regs);
    /* close may fail after consuming the FD: never retry it. Restore cwd
     * after a successful setns even when close reported an error. */
    if (!error) {
        receipt->cwd_error = kern_path(absolute, LOOKUP_FOLLOW, &new_pwd);
        if (!receipt->cwd_error) {
            set_fs_pwd(MSU_DYN_FIELD(current, task_struct, fs), &new_pwd);
            path_put(&new_pwd);
            receipt->effects |= MSU_SU_NS_CWD_RESTORED;
        }
        error = receipt->close_error ? receipt->close_error : receipt->cwd_error;
    }
out:
    if (!(receipt->effects & MSU_SU_NS_APPLIED) && !receipt->operation_error)
        receipt->operation_error = error;
    kfree(buffer);
    return error;
}

static int msu_su_namespace_individual(struct msu_su_namespace_receipt *receipt)
{
    struct pt_regs regs = {0};
    struct path root;
    int error;

    regs.regs[0] = CLONE_NEWNS;
    error = (int)__arm64_sys_unshare(&regs);
    if (error) { receipt->operation_error = error; return error; }
    receipt->effects |= MSU_SU_NS_APPLIED;
    msu_dynamic_get_fs_root(MSU_DYN_FIELD(current, task_struct, fs), &root);
    error = path_mount(NULL, &root, NULL, MS_PRIVATE | MS_REC, NULL);
    path_put(&root);
    if (!error) receipt->effects |= MSU_SU_NS_ROOT_PRIVATE;
    receipt->operation_error = error;
    return error;
}
#else
#define MSU_SU_NAMESPACE_NATIVE_SOURCE 0
#endif

int msu_su_profile_namespace_supported(int mode)
{
    if (mode < 0 || mode > 2) return -EINVAL;
    if (!mode) return 0;
    return MSU_SU_NAMESPACE_NATIVE_SOURCE && MSU_SU_NAMESPACE_NATIVE_VERIFIED ?
        0 : -EOPNOTSUPP;
}

int msu_su_profile_namespace_apply_held(struct msu_su_lease *lease,
    struct msu_su_namespace_receipt *receipt)
{
    const struct msu_su_profile *profile;
    int error;
#if MSU_SU_NAMESPACE_NATIVE_SOURCE
    const struct cred *original;
    struct nsproxy *before;
#endif

    if (!receipt) return -EINVAL;
    memset(receipt, 0, sizeof(*receipt));
    error = msu_su_lease_validate(lease);
    if (error) {
        receipt->validation_error = receipt->error = error;
        return error;
    }
    profile = msu_su_lease_profile(lease);
    if (!profile) {
        receipt->operation_error = receipt->error = -ESTALE;
        return -ESTALE;
    }
    receipt->mode = profile->namespace_mode;
    error = msu_su_profile_namespace_supported(receipt->mode);
    if (!error && !receipt->mode) {
        receipt->effects = MSU_SU_NS_COMPLETED;
        return 0;
    }
    if (error) {
        receipt->operation_error = receipt->error = error;
        return error;
    }
#if MSU_SU_NAMESPACE_NATIVE_SOURCE
    if (in_atomic() || irqs_disabled()) {
        receipt->operation_error = receipt->error = -EWOULDBLOCK;
        return -EWOULDBLOCK;
    }
    if (!MSU_DYN_FIELD(current, task_struct, fs) || !MSU_DYN_FIELD(current, task_struct, files) || !MSU_DYN_FIELD(current, task_struct, nsproxy)) {
        receipt->operation_error = receipt->error = -ESRCH;
        return -ESRCH;
    }
    if (receipt->mode == 1 && atomic_read(&MSU_DYN_FIELD(MSU_DYN_FIELD(current, task_struct, files), files_struct, count)) != 1) {
        receipt->operation_error = receipt->error = -EBUSY;
        return -EBUSY;
    }
    before = MSU_DYN_FIELD(current, task_struct, nsproxy);
    msu_dynamic_get_nsproxy(before);
    /* Validate immediately before changing subjective credentials. */
    error = msu_su_lease_validate(lease);
    if (error) {
        msu_dynamic_put_nsproxy(before);
        receipt->validation_error = receipt->error = error;
        return error;
    }
    original = override_creds(&init_cred);
    error = receipt->mode == 1 ? msu_su_namespace_global(receipt) :
        msu_su_namespace_individual(receipt);
    if (MSU_DYN_FIELD(before, nsproxy, mnt_ns) !=
        MSU_DYN_FIELD(MSU_DYN_FIELD(current, task_struct, nsproxy), nsproxy, mnt_ns))
        receipt->effects |= MSU_SU_NS_CHANGED;
    revert_creds(original);
    msu_dynamic_put_nsproxy(before);
    receipt->validation_error = msu_su_lease_validate(lease);
    /* A primary setns/unshare/mount/setup error survives cleanup/revocation. */
    if (!error) error = receipt->validation_error;
    if (!error) receipt->effects |= MSU_SU_NS_COMPLETED;
#else
    error = -EOPNOTSUPP;
#endif
    receipt->error = error;
    return error;
}
