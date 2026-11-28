// SPDX-License-Identifier: GPL-3.0-only
/* Protected synchronous installation I/O. Native reads retain PID1's live
 * credential; the legacy K22 marker remains scoped to its own carrier.
 * No SELinux policy, global allowance or permanent root grant is added.
 * Disk snapshots are not synchronized with the PMS in-memory commit lock. */
#include <linux/cred.h>
#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/file.h>
#include <linux/init_task.h>
#include <linux/fs.h>
#include <linux/fs_struct.h>
#include <linux/mm.h>
#include <linux/pid.h>
#include <linux/pid_namespace.h>
#include <linux/sched/mm.h>
#include <linux/slab.h>
#include <linux/stat.h>
#include <linux/string.h>
#include <linux/version.h>
#include <linux/xattr.h>
#include "install_records.h"
#include "protected_install_kernel.h"

struct msu_io_scope {
    const struct cred *old_cred;
#ifdef MSU_BOOT_SDK_NATIVE
    const struct cred *io_cred;
#else
    char saved_comm[TASK_COMM_LEN];
#endif
    bool active;
};
struct msu_document {
    struct file *file;
    struct kstat stat;
    void *bytes;
    size_t length;
    int selected;
};
struct msu_install_snapshot {
    struct path root;
    struct msu_document docs[3];
    struct file *apk;
    struct kstat apk_stat;
    struct msu_install_record installation;
    struct msu_record_workspace *workspace;
    struct task_struct *caller_task;
    const struct cred *caller_cred;
    kuid_t caller_uid;
    const struct msu_internal_manager_policy *policy;
    const struct msu_install_monitor *monitor;
};
/* These are compile-time DDK values for reporting, not target-BTF proof. */
const unsigned int msu_resolver_compiled_layout[] __attribute__((section(".msu.layout"))) = {
    offsetof(struct task_struct, cred), offsetof(struct task_struct, comm),
    offsetof(struct cred, euid), offsetof(struct task_struct, fs),
    offsetof(struct task_struct, mm), offsetof(struct vm_area_struct, vm_file),
    offsetof(struct file, f_path), offsetof(struct inode, i_uid)
};
/* Schema 1: six header words, then offset/width/owner-size triples.
 * Optional members are explicitly absent (~0U,0,0), never offset zero.
 * The build feature header comes from this build's actual fs_struct.h.
 * BTF layout and embedded target configuration must both match before load. */
#include "msu_resolver_build_config.h"
#include "manager_dynamic_layout.h"
#define MSU_RESOLVER_FIELD(type, member) \
    offsetof(type, member), sizeof(((type *)0)->member), sizeof(type)
#define MSU_RESOLVER_TYPE(type) 0, sizeof(type), sizeof(type)
const unsigned int msu_resolver_target_layout[] __attribute__((section(".msu.layout"))) = {
    1, 66, MSU_RESOLVER_FS_HAS_LOCK, MSU_RESOLVER_FS_SEQLOCK,
    (IS_ENABLED(CONFIG_SMP) << 0) |
        (IS_ENABLED(CONFIG_PREEMPT_RT) << 1) |
        (IS_ENABLED(CONFIG_PREEMPT_COUNT) << 2) |
        (IS_ENABLED(CONFIG_THREAD_INFO_IN_TASK) << 3) |
        (IS_ENABLED(CONFIG_DEBUG_SPINLOCK) << 4) |
        (IS_ENABLED(CONFIG_DEBUG_LOCK_ALLOC) << 5) |
        (IS_ENABLED(CONFIG_LOCKDEP) << 6) |
        (IS_ENABLED(CONFIG_RWSEM_SPIN_ON_OWNER) << 7) |
        (IS_ENABLED(CONFIG_DEBUG_RWSEMS) << 8) |
        (IS_ENABLED(CONFIG_TRACEPOINTS) << 9) |
        (IS_ENABLED(CONFIG_PREEMPTION) << 10) |
        (IS_ENABLED(CONFIG_PREEMPT) << 11) |
        (IS_ENABLED(CONFIG_PREEMPT_DYNAMIC) << 12) |
        (IS_ENABLED(CONFIG_PROVE_LOCKING) << 13) |
        (IS_ENABLED(CONFIG_PROVE_RCU) << 14) |
        (IS_ENABLED(CONFIG_DEBUG_PREEMPT) << 15) |
        (IS_ENABLED(CONFIG_LOCK_STAT) << 16),
    0, /* Reserved: schema extension requires a new version. */
    MSU_RESOLVER_FIELD(struct task_struct, cred), /* task_struct.cred */
    MSU_RESOLVER_FIELD(struct task_struct, real_cred), /* task_struct.real_cred */
    MSU_RESOLVER_FIELD(struct task_struct, comm), /* task_struct.comm */
    MSU_RESOLVER_FIELD(struct task_struct, fs), /* task_struct.fs */
    MSU_RESOLVER_FIELD(struct task_struct, mm), /* task_struct.mm */
    MSU_RESOLVER_FIELD(struct task_struct, alloc_lock), /* task_struct.alloc_lock */
    MSU_RESOLVER_FIELD(struct task_struct, usage), /* task_struct.usage */
    MSU_RESOLVER_FIELD(struct task_struct, rcu), /* task_struct.rcu */
#if defined(CONFIG_THREAD_INFO_IN_TASK)
    MSU_RESOLVER_FIELD(struct task_struct, thread_info), /* task_struct.thread_info */
#else
    ~0U, 0, 0,
#endif
    MSU_RESOLVER_FIELD(struct cred, uid), /* cred.uid */
    MSU_RESOLVER_FIELD(struct cred, euid), /* cred.euid */
    MSU_RESOLVER_FIELD(struct fs_struct, root), /* fs_struct.root */
    MSU_RESOLVER_FIELD(struct fs_struct, seq), /* fs_struct.seq */
#if MSU_RESOLVER_FS_HAS_LOCK
    MSU_RESOLVER_FIELD(struct fs_struct, lock), /* fs_struct.lock */
#else
    ~0U, 0, 0,
#endif
    MSU_RESOLVER_FIELD(struct path, dentry), /* path.dentry */
    MSU_RESOLVER_FIELD(struct path, mnt), /* path.mnt */
    MSU_RESOLVER_FIELD(struct dentry, d_inode), /* dentry.d_inode */
    MSU_RESOLVER_FIELD(struct file, f_path), /* file.f_path */
    MSU_RESOLVER_FIELD(struct file, f_inode), /* file.f_inode */
    MSU_RESOLVER_FIELD(struct inode, i_uid), /* inode.i_uid */
    MSU_RESOLVER_FIELD(struct inode, i_gid), /* inode.i_gid */
    MSU_RESOLVER_FIELD(struct inode, i_mode), /* inode.i_mode */
    MSU_RESOLVER_FIELD(struct vm_area_struct, vm_file), /* vm_area_struct.vm_file */
    MSU_RESOLVER_FIELD(struct vm_area_struct, vm_end), /* vm_area_struct.vm_end */
    MSU_RESOLVER_FIELD(struct mm_struct, mmap_lock), /* mm_struct.mmap_lock */
    MSU_RESOLVER_TYPE(struct rw_semaphore), /* rw_semaphore.__size */
#if !defined(CONFIG_PREEMPT_RT)
    MSU_RESOLVER_FIELD(struct rw_semaphore, count), /* rw_semaphore.count */
#else
    ~0U, 0, 0,
#endif
#if !defined(CONFIG_PREEMPT_RT)
    MSU_RESOLVER_FIELD(struct rw_semaphore, owner), /* rw_semaphore.owner */
#else
    ~0U, 0, 0,
#endif
#if !defined(CONFIG_PREEMPT_RT) && defined(CONFIG_RWSEM_SPIN_ON_OWNER)
    MSU_RESOLVER_FIELD(struct rw_semaphore, osq), /* rw_semaphore.osq */
#else
    ~0U, 0, 0,
#endif
#if !defined(CONFIG_PREEMPT_RT)
    MSU_RESOLVER_FIELD(struct rw_semaphore, wait_lock), /* rw_semaphore.wait_lock */
#else
    ~0U, 0, 0,
#endif
#if !defined(CONFIG_PREEMPT_RT)
    MSU_RESOLVER_FIELD(struct rw_semaphore, wait_list), /* rw_semaphore.wait_list */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_DEBUG_LOCK_ALLOC)
    MSU_RESOLVER_FIELD(struct rw_semaphore, dep_map), /* rw_semaphore.dep_map */
#else
    ~0U, 0, 0,
#endif
#if !defined(CONFIG_PREEMPT_RT) && defined(CONFIG_DEBUG_RWSEMS)
    MSU_RESOLVER_FIELD(struct rw_semaphore, magic), /* rw_semaphore.magic */
#else
    ~0U, 0, 0,
#endif
    MSU_RESOLVER_TYPE(struct kstat), /* kstat.__size */
    MSU_RESOLVER_FIELD(struct kstat, ino), /* kstat.ino */
    MSU_RESOLVER_FIELD(struct kstat, dev), /* kstat.dev */
    MSU_RESOLVER_FIELD(struct kstat, size), /* kstat.size */
    MSU_RESOLVER_FIELD(struct kstat, nlink), /* kstat.nlink */
    MSU_RESOLVER_FIELD(struct kstat, mode), /* kstat.mode */
    MSU_RESOLVER_FIELD(struct kstat, uid), /* kstat.uid */
    MSU_RESOLVER_FIELD(struct kstat, gid), /* kstat.gid */
    MSU_RESOLVER_FIELD(struct kstat, mtime), /* kstat.mtime */
    MSU_RESOLVER_FIELD(struct kstat, ctime), /* kstat.ctime */
    MSU_RESOLVER_FIELD(struct timespec64, tv_sec), /* timespec64.tv_sec */
    MSU_RESOLVER_FIELD(struct timespec64, tv_nsec), /* timespec64.tv_nsec */
    MSU_RESOLVER_TYPE(spinlock_t), /* spinlock_t.__size */
#if !defined(CONFIG_PREEMPT_RT)
    MSU_RESOLVER_FIELD(spinlock_t, rlock), /* spinlock_t.rlock */
#else
    ~0U, 0, 0,
#endif
    MSU_RESOLVER_FIELD(raw_spinlock_t, raw_lock), /* raw_spinlock_t.raw_lock */
    MSU_RESOLVER_TYPE(arch_spinlock_t), /* arch_spinlock_t.__size */
    MSU_RESOLVER_FIELD(atomic_t, counter), /* atomic_t.counter */
    MSU_RESOLVER_FIELD(atomic_long_t, counter), /* atomic_long_t.counter */
    MSU_RESOLVER_FIELD(refcount_t, refs), /* refcount_t.refs */
#if MSU_RESOLVER_FS_SEQLOCK
    MSU_RESOLVER_FIELD(struct fs_struct, seq.lock), /* fs_struct.seq.lock */
#else
    ~0U, 0, 0,
#endif
    MSU_RESOLVER_FIELD(struct task_struct, usage.refs.counter), /* task_struct.usage.refs.counter */
#if defined(CONFIG_PREEMPT_COUNT)
    MSU_RESOLVER_FIELD(struct thread_info, preempt_count), /* thread_info.preempt_count */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_SMP)
    MSU_RESOLVER_FIELD(arch_spinlock_t, val.counter), /* arch_spinlock_t.val.counter */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_DEBUG_SPINLOCK)
    MSU_RESOLVER_FIELD(raw_spinlock_t, magic), /* raw_spinlock_t.magic */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_DEBUG_SPINLOCK)
    MSU_RESOLVER_FIELD(raw_spinlock_t, owner_cpu), /* raw_spinlock_t.owner_cpu */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_DEBUG_SPINLOCK)
    MSU_RESOLVER_FIELD(raw_spinlock_t, owner), /* raw_spinlock_t.owner */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_DEBUG_LOCK_ALLOC)
    MSU_RESOLVER_FIELD(raw_spinlock_t, dep_map), /* raw_spinlock_t.dep_map */
#else
    ~0U, 0, 0,
#endif
#if defined(CONFIG_DEBUG_LOCK_ALLOC)
    MSU_RESOLVER_FIELD(spinlock_t, dep_map), /* spinlock_t.dep_map */
#else
    ~0U, 0, 0,
#endif
    MSU_RESOLVER_FIELD(struct rcu_head, next), /* callback_head.next */
    MSU_RESOLVER_FIELD(struct rcu_head, func), /* callback_head.func */
    MSU_RESOLVER_FIELD(kuid_t, val), /* kuid_t.val */
    MSU_RESOLVER_FIELD(kgid_t, val), /* kgid_t.val */
    MSU_RESOLVER_FIELD(struct thread_info, flags), /* thread_info.flags */
};
_Static_assert(sizeof(msu_resolver_target_layout) / sizeof(unsigned int) ==
    6 + 3 * 66, "resolver target layout schema");
#undef MSU_RESOLVER_TYPE
#undef MSU_RESOLVER_FIELD
#ifdef MSU_BOOT_SDK_NATIVE
static const struct cred *msu_pid1_io_cred(void)
{
    struct task_struct *init;
    const struct cred *cred;
    rcu_read_lock();
    init = find_task_by_pid_ns(1, &init_pid_ns);
    if (init) msu_dynamic_get_task_struct(init);
    rcu_read_unlock();
    if (!init) return ERR_PTR(-ENOENT);
    /* PID1's live credential carries the platform init domain. The static
     * init_cred remains in the kernel domain and does not grant Android /data
     * reads. Retain this immutable snapshot only for synchronous internal I/O. */
    cred = get_task_cred(init);
    msu_dynamic_put_task_struct(init);
    return cred;
}
#endif
static int msu_io_enter(struct msu_io_scope *scope)
{
#ifndef MSU_BOOT_SDK_NATIVE
    static const u64 marker = 0xf000f000f000f000ULL;
#endif
    memset(scope, 0, sizeof(*scope));
    if (!msu_runtime_layout_ready()) return -EPROTO;
    /* The generated hook resolves comm independently from cred. Keep the
     * caller's credential contract without imposing the old field adjacency. */
    if (msu_dynamic_current_cred() != rcu_access_pointer(MSU_DYN_FIELD(current, task_struct, real_cred)))
        return -EOPNOTSUPP;
#ifdef MSU_BOOT_SDK_NATIVE
    scope->io_cred = msu_pid1_io_cred();
    if (IS_ERR(scope->io_cred)) return PTR_ERR(scope->io_cred);
    scope->old_cred = override_creds(scope->io_cred);
#else
    msu_dynamic_task_lock(current);
    memcpy(scope->saved_comm, MSU_DYN_FIELD(current, task_struct, comm), TASK_COMM_LEN);
    memcpy(MSU_DYN_FIELD(current, task_struct, comm), &marker, sizeof(marker));
    msu_dynamic_task_unlock(current);
    scope->old_cred = override_creds(&init_cred);
#endif
    scope->active = true;
    return 0;
}
static void msu_io_leave(struct msu_io_scope *scope)
{
    if (!scope->active) return;
#ifndef MSU_BOOT_SDK_NATIVE
    /* Restore the marker before reverting root subjective creds. Signals only
     * resume user handlers after this synchronous kernel invocation returns.
     * No spinlock is held across sleeping I/O and other threads keep own creds. */
    msu_dynamic_task_lock(current);
    memcpy(MSU_DYN_FIELD(current, task_struct, comm), scope->saved_comm, TASK_COMM_LEN);
    msu_dynamic_task_unlock(current);
#endif
    revert_creds(scope->old_cred);
#ifdef MSU_BOOT_SDK_NATIVE
    msu_dynamic_put_cred(scope->io_cred);
#endif
    scope->active = false;
}
static int msu_pid1_root(struct path *root)
{
    struct task_struct *init;
    int rc = -ENOENT;
    memset(root, 0, sizeof(*root));
    rcu_read_lock();
    init = find_task_by_pid_ns(1, &init_pid_ns);
    if (init) msu_dynamic_get_task_struct(init);
    rcu_read_unlock();
    if (!init) return rc;
    msu_dynamic_task_lock(init);
    if (MSU_DYN_FIELD(init, task_struct, fs)) { msu_dynamic_get_fs_root(MSU_DYN_FIELD(init, task_struct, fs), root); rc = 0; }
    msu_dynamic_task_unlock(init);
    msu_dynamic_put_task_struct(init);
    return rc;
}
static bool msu_trusted_inode(const struct inode *inode, bool directory)
{
    unsigned int owner = __kuid_val(MSU_DYN_FIELD(inode, inode, i_uid)), group = __kgid_val(MSU_DYN_FIELD(inode, inode, i_gid));
    umode_t mode = MSU_DYN_FIELD(inode, inode, i_mode);
    return (directory ? S_ISDIR(mode) : S_ISREG(mode)) &&
        (owner == 0 || owner == 1000) && !(mode & S_IWOTH) &&
        (!(mode & S_IWGRP) || group == 0 || group == 1000);
}
static struct file *msu_open_at(const struct path *root, const char *name, int flags)
{
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 15, 0)
    return file_open_root(root->dentry, root->mnt, name, flags | O_NOFOLLOW, 0);
#else
    return file_open_root(root, name, flags | O_NOFOLLOW, 0);
#endif
}
/* Every ancestor is opened from the retained PID1 filesystem root, checked,
 * and held as a path object. O_NOFOLLOW therefore covers EVERY component. */
static struct file *msu_open_protected(const struct path *root, const char *absolute,
    bool final_directory, const struct msu_install_monitor *monitor)
{
    struct path walk = *root;
    struct file *file = NULL;
    const char *start;
    char part[256];
    size_t n;
    int flags;
    if (!absolute || absolute[0] != '/' || !absolute[1]) return ERR_PTR(-EINVAL);
    if (monitor) {
        int error = monitor->watch_directory(monitor->context,
            msu_dynamic_d_inode(root->dentry), NULL);
        if (error) return ERR_PTR(error);
    }
    if (!msu_trusted_inode(msu_dynamic_d_inode(root->dentry), true)) return ERR_PTR(-EINVAL);
    path_get(&walk);
    start = absolute + 1;
    for (;;) {
        const char *slash = start;
        while (*slash && *slash != '/') ++slash;
        n = (size_t)(slash - start);
        if (!*slash) slash = NULL;
        if (!n || n >= sizeof(part) || (n == 1 && start[0] == '.') ||
            (n == 2 && start[0] == '.' && start[1] == '.')) {
            file = ERR_PTR(-EINVAL); break;
        }
        memcpy(part, start, n); part[n] = 0;
        flags = O_RDONLY | ((slash || final_directory) ? O_DIRECTORY : 0);
        if (monitor) {
            int error = monitor->watch_directory(monitor->context,
                msu_dynamic_d_inode(walk.dentry), part);
            if (error) { file = ERR_PTR(error); break; }
        }
        file = msu_open_at(&walk, part, flags);
        if (IS_ERR(file)) break;
        if ((slash || final_directory) && monitor) {
            int error = monitor->watch_directory(monitor->context,
                msu_dynamic_file_inode(file), NULL);
            if (error) { fput(file); file = ERR_PTR(error); break; }
        }
        if (!msu_trusted_inode(msu_dynamic_file_inode(file), slash || final_directory)) {
            fput(file); file = ERR_PTR(-EPERM); break;
        }
        if (!slash) break;
        path_put(&walk); walk = MSU_DYN_FIELD(file, file, f_path); path_get(&walk);
        fput(file); file = NULL; start = slash + 1;
    }
    path_put(&walk);
    return file;
}
struct file *msu_kernel_open_installation_path(const char *absolute, bool directory)
{
    struct msu_io_scope scope;
    struct path root;
    struct file *file;
    int error = msu_io_enter(&scope);
    if (error) return ERR_PTR(error);
    error = msu_pid1_root(&root);
    if (error) file = ERR_PTR(error);
    else {
        file = msu_open_protected(&root, absolute, directory, NULL);
        path_put(&root);
    }
    msu_io_leave(&scope);
    return file;
}

ssize_t msu_kernel_read_installation_record(struct file *directory, void *buffer, size_t bytes)
{
    struct msu_io_scope scope;
    ssize_t count;
    int error;
    if (!directory || !buffer || !bytes) return -EINVAL;
    error = msu_io_enter(&scope);
    if (error) return error;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 3, 0)
    count = vfs_getxattr(MSU_DYN_FIELD(MSU_DYN_FIELD(directory, file, f_path).mnt, vfsmount, mnt_idmap), MSU_DYN_FIELD(directory, file, f_path).dentry,
        "trusted.maplesu.install.v1", buffer, bytes);
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 12, 0)
    count = vfs_getxattr(MSU_DYN_FIELD_AS(MSU_DYN_FIELD(directory, file, f_path).mnt, vfsmount, mnt_user_ns, mnt_userns), MSU_DYN_FIELD(directory, file, f_path).dentry,
        "trusted.maplesu.install.v1", buffer, bytes);
#else
    count = vfs_getxattr(MSU_DYN_FIELD(directory, file, f_path).dentry, "trusted.maplesu.install.v1", buffer, bytes);
#endif
    msu_io_leave(&scope);
    return count;
}

/* Boot identifier is only a stale deletion-fence discriminator. */
int msu_kernel_installation_boot_id(char boot[40])
{
    struct file *file;
    struct msu_io_scope scope;
    struct path root;
    char bytes[40] = {0};
    loff_t position = 0;
    ssize_t count;
    unsigned int i;
    int error;
    if (!boot) return -EINVAL;
    error = msu_io_enter(&scope);
    if (error) return error;
    error = msu_pid1_root(&root);
    if (error) goto leave;
    file = msu_open_protected(&root, "/proc/sys/kernel/random/boot_id", false, NULL);
    path_put(&root);
    if (IS_ERR(file)) { error = PTR_ERR(file); goto leave; }
    count = kernel_read(file, bytes, sizeof(bytes), &position);
    fput(file);
    if (count < 0) { error = count; goto leave; }
    if (count != 37 || bytes[36] != '\n') { error = -EBADMSG; goto leave; }
    for (i = 0; i < 36; ++i) {
        char c = bytes[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (c != '-') { error = -EBADMSG; goto leave; }
        } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
                     (c >= 'A' && c <= 'F'))) { error = -EBADMSG; goto leave; }
    }
    memset(boot, 0, 40);
    memcpy(boot, bytes, 36);
    error = 0;
leave:
    msu_io_leave(&scope);
    return error;
}

int msu_kernel_write_installation_guard(struct file *directory, const void *buffer, size_t bytes)
{
    struct msu_io_scope scope;
    int error;
    if (!directory || !buffer || !bytes) return -EINVAL;
    error = msu_io_enter(&scope);
    if (error) return error;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 3, 0)
    error = vfs_setxattr(MSU_DYN_FIELD(MSU_DYN_FIELD(directory, file, f_path).mnt, vfsmount, mnt_idmap), MSU_DYN_FIELD(directory, file, f_path).dentry,
        "trusted.maplesu.install.host.v1", buffer, bytes, 0);
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 12, 0)
    error = vfs_setxattr(MSU_DYN_FIELD_AS(MSU_DYN_FIELD(directory, file, f_path).mnt, vfsmount, mnt_user_ns, mnt_userns), MSU_DYN_FIELD(directory, file, f_path).dentry,
        "trusted.maplesu.install.host.v1", buffer, bytes, 0);
#else
    error = vfs_setxattr(MSU_DYN_FIELD(directory, file, f_path).dentry, "trusted.maplesu.install.host.v1", buffer, bytes, 0);
#endif
    if (!error) error = vfs_fsync(directory, 0);
    msu_io_leave(&scope);
    return error;
}

static int msu_stat(struct file *file, struct kstat *stat)
{ return vfs_getattr(&MSU_DYN_FIELD(file, file, f_path), stat, STATX_BASIC_STATS, AT_STATX_SYNC_AS_STAT); }
static bool msu_same_stat(const struct kstat *a, const struct kstat *b)
{
    return a->ino == b->ino && a->dev == b->dev && a->size == b->size &&
        a->nlink == b->nlink && a->mode == b->mode &&
        uid_eq(a->uid, b->uid) && gid_eq(a->gid, b->gid) &&
        a->mtime.tv_sec == b->mtime.tv_sec && a->mtime.tv_nsec == b->mtime.tv_nsec &&
        a->ctime.tv_sec == b->ctime.tv_sec && a->ctime.tv_nsec == b->ctime.tv_nsec;
}
int msu_kernel_stat_protected_path(const char *absolute, bool directory,
    struct file **held, struct kstat *stat)
{
    struct msu_io_scope scope;
    struct path root;
    struct file *file = NULL;
    int error;
    if (!held || !stat) return -EINVAL;
    *held = NULL;
    error = msu_io_enter(&scope);
    if (error) return error;
    error = msu_pid1_root(&root);
    if (error) goto out;
    file = msu_open_protected(&root, absolute, directory, NULL);
    path_put(&root);
    if (IS_ERR(file)) { error = PTR_ERR(file); file = NULL; goto out; }
    error = msu_stat(file, stat);
    if (!error) { *held = file; file = NULL; }
out:
    if (file) fput(file);
    msu_io_leave(&scope);
    return error;
}
int msu_kernel_stat_observed_app_directory(u32 uid,const char *package,
    struct file **held,struct kstat *stat)
{
    struct msu_io_scope scope;
    struct path root;
    struct file *parent = NULL,*file = NULL;
    char path[80];
    unsigned int i,attempts;
    int error;
    if (!held || !stat || !package || !package[0] || uid > 0x7fffffffU) return -EINVAL;
    *held = NULL;
    for (i = 0; i < 256 && package[i]; ++i) {
        unsigned char c = package[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '.' || c == '_')) return -EINVAL;
    }
    if (!i || i == 256 || !strcmp(package,".") || !strcmp(package,"..")) return -EINVAL;
    if (uid == 1000 && !strcmp(package,"android"))
        return msu_kernel_stat_protected_path("/data/system",true,held,stat);
    error = msu_io_enter(&scope);
    if (error) return error;
    error = msu_pid1_root(&root);
    if (error) goto out;
    attempts = uid / 100000U ? 2 : 3;
    for (i = 0; i < attempts; ++i) {
        if (i < 2) snprintf(path,sizeof(path),"/data/%s/%u",i ? "user" : "user_de",uid / 100000U);
        else memcpy(path,"/data/data",11);
        parent = msu_open_protected(&root,path,true,NULL);
        if (IS_ERR(parent)) {
            error = PTR_ERR(parent); parent = NULL;
            if (error == -ENOENT) continue;
            break;
        }
        file = msu_open_at(&parent->f_path,package,O_RDONLY | O_DIRECTORY);
        fput(parent); parent = NULL;
        if (IS_ERR(file)) {
            error = PTR_ERR(file); file = NULL;
            if (error == -ENOENT) continue;
            break;
        }
        error = msu_stat(file,stat);
        if (!error && (!S_ISDIR(stat->mode) || __kuid_val(stat->uid) != uid ||
            (stat->mode & S_IWOTH) || ((stat->mode & S_IWGRP) && __kgid_val(stat->gid) != uid)))
            error = -EPERM;
        if (!error) { *held = file; file = NULL; }
        break;
    }
    path_put(&root);
out:
    if (parent) fput(parent);
    if (file) fput(file);
    msu_io_leave(&scope);
    return error;
}
int msu_kernel_iterate_protected_directory(const char *absolute,struct dir_context *context)
{
    struct msu_io_scope scope;
    struct path root;
    struct file *file = NULL;
    int error;
    if (!context || !context->actor) return -EINVAL;
    error = msu_io_enter(&scope);
    if (error) return error;
    error = msu_pid1_root(&root);
    if (error) goto out;
    file = msu_open_protected(&root,absolute,true,NULL);
    path_put(&root);
    if (IS_ERR(file)) { error = PTR_ERR(file); file = NULL; goto out; }
    error = iterate_dir(file,context);
out:
    if (file) fput(file);
    msu_io_leave(&scope);
    return error;
}
int msu_kernel_read_protected_document(const char *absolute, size_t limit,
    struct file **held, void **bytes, size_t *length, struct kstat *stat)
{
    struct msu_io_scope scope;
    struct path root;
    struct file *file = NULL;
    struct kstat after;
    void *data = NULL;
    loff_t position = 0;
    ssize_t count;
    int error;
    if (!held || !bytes || !length || !stat || !limit || limit > MSU_RECORD_LIMIT)
        return -EINVAL;
    *held = NULL; *bytes = NULL; *length = 0;
    error = msu_io_enter(&scope);
    if (error) return error;
    error = msu_pid1_root(&root);
    if (error) goto out;
    file = msu_open_protected(&root, absolute, false, NULL);
    path_put(&root);
    if (IS_ERR(file)) { error = PTR_ERR(file); file = NULL; goto out; }
    error = msu_stat(file, stat);
    if (error) goto out;
    if (stat->size < 0 || stat->size > limit || !stat->nlink) {
        error = -EFBIG; goto out;
    }
    data = kvzalloc(stat->size ? stat->size : 1, GFP_KERNEL);
    if (!data) { error = -ENOMEM; goto out; }
    count = kernel_read(file, data, stat->size, &position);
    if (count < 0) { error = count; goto out; }
    if (count != stat->size) { error = -EIO; goto out; }
    error = msu_stat(file, &after);
    if (!error && !msu_same_stat(stat, &after)) error = -EAGAIN;
    if (!error) {
        *held = file; *bytes = data; *length = stat->size;
        file = NULL; data = NULL;
    }
out:
    if (file) fput(file);
    kvfree(data);
    msu_io_leave(&scope);
    return error;
}
static void msu_drop_doc(struct msu_document *doc)
{
    if (doc->file) fput(doc->file);
    kvfree(doc->bytes); memset(doc, 0, sizeof(*doc));
}
static int msu_read_doc(struct msu_document *doc, struct file *file, int selected)
{
    struct kstat after;
    loff_t pos = 0;
    ssize_t got;
    int rc;
    memset(doc, 0, sizeof(*doc)); doc->selected = selected;
    doc->file = file;
    rc = msu_stat(file, &doc->stat);
    if (rc) return rc;
    if (doc->stat.size <= 0 || doc->stat.size > MSU_RECORD_LIMIT || !doc->stat.nlink) return -EFBIG;
    doc->length = (size_t)doc->stat.size;
    doc->bytes = kvzalloc(doc->length, GFP_KERNEL);
    if (!doc->bytes) return -ENOMEM;
    got = kernel_read(file, doc->bytes, doc->length, &pos);
    if (got < 0) return (int)got;
    if (got != (ssize_t)doc->length) return -EIO;
    rc = msu_stat(file, &after);
    return rc ? rc : msu_same_stat(&doc->stat, &after) ? 0 : -EAGAIN;
}
/* Recovery precedence follows AOSP ResilientAtomicFile, but does not mutate any
 * PM file. Read/permission errors do not masquerade as absent/default state. */
static int msu_select_doc(const struct path *root, const char *const paths[],
    unsigned int count, bool optional, struct msu_document *doc,
    const struct msu_install_monitor *monitor)
{
    unsigned int i;
    struct file *file;
    memset(doc, 0, sizeof(*doc)); doc->selected = -1;
    for (i = 0; i < count; ++i) {
        file = msu_open_protected(root, paths[i], false, monitor);
        if (IS_ERR(file)) { if (PTR_ERR(file) == -ENOENT) continue; return (int)PTR_ERR(file); }
        return msu_read_doc(doc, file, (int)i);
    }
    return optional ? 0 : -ENOENT;
}
static int msu_read_snapshot_docs(struct msu_install_snapshot *snapshot)
{
    static const char *const list[] = {"/data/system/packages.list"};
    static const char *const global[] = {
        "/data/system/packages-backup.xml", "/data/system/packages.xml",
        "/data/system/packages.xml.reservecopy"};
    char userdir[80], backup[128], main[128], reserve[128];
    const char *user[] = {backup, main, reserve};
    struct file *dir;
    int rc;
    rc = msu_select_doc(&snapshot->root, list, 1, false, &snapshot->docs[0], snapshot->monitor);
    if (rc) return rc;
    rc = msu_select_doc(&snapshot->root, global, 3, false, &snapshot->docs[1], snapshot->monitor);
    if (rc) return rc;
    snprintf(userdir, sizeof(userdir), "/data/system/users/%u", snapshot->installation.user_id);
    dir = msu_open_protected(&snapshot->root, userdir, true, snapshot->monitor);
    if (IS_ERR(dir)) return (int)PTR_ERR(dir);
    fput(dir);
    snprintf(backup, sizeof(backup), "%s/package-restrictions-backup.xml", userdir);
    snprintf(main, sizeof(main), "%s/package-restrictions.xml", userdir);
    snprintf(reserve, sizeof(reserve), "%s/package-restrictions.xml.reservecopy", userdir);
    return msu_select_doc(&snapshot->root, user, 3, true, &snapshot->docs[2], snapshot->monitor);
}
static bool msu_caller_maps_apk(struct file *apk)
{
    struct mm_struct *mm = get_task_mm(current);
    struct vm_area_struct *vma;
    unsigned long address = 0;
    bool found = false;
    if (!mm) return false;
    down_read(&MSU_DYN_FIELD(mm, mm_struct, mmap_lock));
    /* Let the target's own implementation traverse its VMA index. The payload
     * does not embed a DDK-linked list or Maple Tree iterator. */
    while ((vma = find_vma(mm, address)) != NULL) {
        if (MSU_DYN_FIELD(vma, vm_area_struct, vm_file) && msu_dynamic_file_inode(MSU_DYN_FIELD(vma, vm_area_struct, vm_file)) == msu_dynamic_file_inode(apk)) { found = true; break; }
        if (MSU_DYN_FIELD(vma, vm_area_struct, vm_end) <= address) break;
        address = MSU_DYN_FIELD(vma, vm_area_struct, vm_end);
    }
    up_read(&MSU_DYN_FIELD(mm, mm_struct, mmap_lock)); mmput(mm);
    return found;
}
static int msu_apk_read(void *opaque, u64 offset, void *bytes, size_t count)
{
    struct msu_install_snapshot *snapshot = opaque;
    loff_t pos;
    ssize_t got;
    if (offset > (u64)snapshot->apk_stat.size || count > (u64)snapshot->apk_stat.size - offset) return -EINVAL;
    pos = (loff_t)offset;
    got = kernel_read(snapshot->apk, bytes, count, &pos);
    return got < 0 ? (int)got : got == (ssize_t)count ? 0 : -EIO;
}
static int msu_apk_sha(void *opaque, const void *bytes, size_t count, u8 digest[32])
{
    struct msu_install_snapshot *snapshot = opaque;
    return snapshot->policy->sha256(bytes, count, digest);
}
static int msu_recheck_snapshot(struct msu_install_snapshot *snapshot)
{
    struct msu_install_snapshot *fresh;
    struct file *apk = NULL;
    struct kstat stat;
    unsigned int i;
    int rc;
    fresh = kzalloc(sizeof(*fresh), GFP_KERNEL);
    if (!fresh) return -ENOMEM;
    rc = msu_pid1_root(&fresh->root);
    if (rc) goto out;
    if (!path_equal(&fresh->root, &snapshot->root)) { rc = -EAGAIN; goto out; }
    fresh->installation.user_id = snapshot->installation.user_id;
    fresh->monitor = snapshot->monitor;
    rc = msu_read_snapshot_docs(fresh);
    if (rc) goto out;
    for (i = 0; i < 3; ++i) {
        struct msu_document *a = &snapshot->docs[i], *b = &fresh->docs[i];
        if (a->selected != b->selected || a->length != b->length ||
            (a->length && (msu_dynamic_file_inode(a->file) != msu_dynamic_file_inode(b->file) ||
             !msu_same_stat(&a->stat, &b->stat) || memcmp(a->bytes, b->bytes, a->length)))) {
            rc = -EAGAIN; goto out;
        }
    }
    apk = msu_open_protected(&fresh->root, snapshot->installation.apk_path, false, snapshot->monitor);
    if (IS_ERR(apk)) { rc = (int)PTR_ERR(apk); apk = NULL; goto out; }
    rc = msu_stat(apk, &stat);
    if (rc) goto out;
    if (msu_dynamic_file_inode(apk) != msu_dynamic_file_inode(snapshot->apk) ||
        !msu_same_stat(&snapshot->apk_stat, &stat) || !msu_caller_maps_apk(apk) ||
        current != snapshot->caller_task ||
        rcu_access_pointer(MSU_DYN_FIELD(current, task_struct, real_cred)) != snapshot->caller_cred ||
        !uid_eq(MSU_DYN_FIELD(rcu_access_pointer(MSU_DYN_FIELD(current, task_struct, real_cred)), cred, uid), snapshot->caller_uid)) rc = -EAGAIN;
out:
    if (apk) fput(apk);
    for (i = 0; i < 3; ++i) msu_drop_doc(&fresh->docs[i]);
    if (fresh->root.dentry) path_put(&fresh->root);
    kfree(fresh); return rc;
}
int msu_kernel_verify_manager_installation_watched(
    const struct msu_internal_manager_policy *policy, u32 *generation,
    const struct msu_install_monitor *monitor)
{
    struct msu_install_snapshot *snapshot;
    struct msu_apk_source source;
    struct msu_io_scope scope;
    struct msu_record_bytes docs[3];
    kuid_t caller_uid = msu_dynamic_current_uid();
    u32 full_uid = __kuid_val(caller_uid), app_id = full_uid % 100000U, result = 0;
    unsigned int i;
    int rc;
    if (generation) *generation = 0;
    if (!generation || !policy || !policy->manager_package || !policy->sha256 ||
        (monitor && !monitor->watch_directory) ||
        app_id < 10000 || app_id > 19999 || full_uid > 2147483647U) return -EPERM;
    snapshot = kzalloc(sizeof(*snapshot), GFP_KERNEL);
    if (!snapshot) return -ENOMEM;
    snapshot->caller_uid = caller_uid; snapshot->caller_task = current;
    snapshot->policy = policy; snapshot->monitor = monitor;
    snapshot->installation.user_id = full_uid / 100000U;
    rc = msu_io_enter(&scope);
    if (rc) goto free_snapshot;
    snapshot->caller_cred = scope.old_cred;
    rc = msu_pid1_root(&snapshot->root);
    if (rc) goto out;
    rc = msu_read_snapshot_docs(snapshot);
    if (rc) goto out;
    snapshot->workspace = kvzalloc(sizeof(*snapshot->workspace), GFP_KERNEL);
    if (!snapshot->workspace) { rc = -ENOMEM; goto out; }
    for (i = 0; i < 3; ++i) {
        docs[i].data = snapshot->docs[i].bytes; docs[i].length = snapshot->docs[i].length;
    }
    rc = msu_read_install_records(docs[0], docs[1], docs[2], full_uid,
        policy->manager_package, snapshot->workspace, &snapshot->installation);
    if (rc) { rc = -EPERM; goto out; }
    snapshot->apk = msu_open_protected(&snapshot->root, snapshot->installation.apk_path, false, snapshot->monitor);
    if (IS_ERR(snapshot->apk)) { rc = (int)PTR_ERR(snapshot->apk); snapshot->apk = NULL; goto out; }
    rc = msu_stat(snapshot->apk, &snapshot->apk_stat);
    if (rc) goto out;
    if (snapshot->apk_stat.size <= 0 || !snapshot->apk_stat.nlink || !msu_caller_maps_apk(snapshot->apk)) { rc = -EPERM; goto out; }
    source.context = snapshot; source.length = (u64)snapshot->apk_stat.size;
    source.read_at = msu_apk_read; source.sha256 = msu_apk_sha;
    if (!msu_manager_apk_identity(&source, &policy->pin, &result)) {
        struct msu_manager_apk_pin current_signer;
        /* The protected snapshot is the platform's verified signing history.
         * APK proof-of-rotation bytes alone never extend this immutable pin. */
        rc = msu_read_install_signing(docs[1], policy->manager_package,
            snapshot->workspace, &policy->pin, snapshot, msu_apk_sha, &current_signer);
        if (rc || !msu_manager_installed_apk_identity(&source, &current_signer, &result)) {
            rc = -EPERM; goto out;
        }
    }
    rc = msu_recheck_snapshot(snapshot);
out:
    if (snapshot->apk) fput(snapshot->apk);
    for (i = 0; i < 3; ++i) msu_drop_doc(&snapshot->docs[i]);
    kvfree(snapshot->workspace);
    if (snapshot->root.dentry) path_put(&snapshot->root);
    msu_io_leave(&scope);
    /* No root/key is exposed before the caller's original creds/name return. */
    if (!rc && (msu_dynamic_current_cred() != scope.old_cred ||
        rcu_access_pointer(MSU_DYN_FIELD(current, task_struct, real_cred)) != snapshot->caller_cred ||
        !uid_eq(msu_dynamic_current_uid(), caller_uid) || current != snapshot->caller_task)) rc = -EAGAIN;
    if (!rc) *generation = result;
free_snapshot:
    kfree(snapshot); return rc;
}

int msu_kernel_verify_manager_installation(
    const struct msu_internal_manager_policy *policy, u32 *generation)
{
    return msu_kernel_verify_manager_installation_watched(policy, generation, NULL);
}
