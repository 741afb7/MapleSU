// SPDX-License-Identifier: GPL-3.0-only
#include <linux/capability.h>
#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/fs.h>
#include <linux/fs_struct.h>
#include <linux/irqflags.h>
#include <linux/mount.h>
#include <linux/namei.h>
#include <linux/nsproxy.h>
#include <linux/preempt.h>
#include <linux/sched.h>
#include <linux/security.h>
#include <linux/string.h>
#include <linux/user_namespace.h>
#include <uapi/linux/mount.h>
#include "su_nonroot_transition.h"
#include "su_install_registry.h"
#include "manager_installation_state.h"
#include "manager_management.h"
#include "manager_dynamic_layout.h"

/* Requires native imports/KCFI/layout AND the real post-commit caller/source.
 * Compiling with 1 exercises the branch, not target qualification. */
#ifndef MSU_SU_NONROOT_NATIVE_VERIFIED
#define MSU_SU_NONROOT_NATIVE_VERIFIED 0
#endif

extern int path_umount(struct path *path, int flags);
#ifndef path_mount
extern int path_mount(const char *dev_name, struct path *path,
    const char *type_page, unsigned long flags, void *data_page);
#endif
_Static_assert(__builtin_types_compatible_p(typeof(&path_umount),
    int (*)(struct path *, int)), "native path_umount ABI");

static bool application_uid(u32 uid)
{
    u32 app = uid % 100000U;
    /* Fixed KSU also handles webview_zygote 1053. This registry has no Profile
     * authority for that UID; do not manufacture an isolated/global default. */
    return uid <= 0x7fffffffU && ((app >= 10000U && app <= 19999U) ||
        (app >= 99000U && app <= 99999U));
}

static int same_source(struct msu_su_registry *registry,
    const struct msu_su_nonroot_mounts *mounts, const struct cred *original,
    struct nsproxy *ns, unsigned long exec_id)
{
    maplesu_installation_state_v3 live = {0};
    enum msu_su_non_root_umount_policy policy;
    const maplesu_installation_state_v3 *held = &mounts->installation;
    int error;
    if (msu_dynamic_current_cred() != original || msu_dynamic_current_real_cred() != original ||
        READ_ONCE(MSU_DYN_FIELD(current, task_struct, self_exec_id)) != exec_id ||
        (READ_ONCE(MSU_DYN_FIELD(current, task_struct, flags)) & PF_EXITING) ||
        READ_ONCE(MSU_DYN_FIELD(current, task_struct, nsproxy)) != ns || MSU_DYN_FIELD(ns, nsproxy, mnt_ns) != mounts->mnt_ns ||
        __kuid_val(MSU_DYN_FIELD(original, cred, uid)) != mounts->full_uid || mounts->registry != registry ||
        (u64)atomic64_read(mounts->live_generation) != mounts->generation)
        return -ESTALE;
    error = msu_installation_read(&live);
    if (error) return error;
    if (!held->record.active || held->flags || !live.record.active || live.flags ||
        !(held->host.low | held->host.high) || !held->device || !held->inode ||
        live.host.low != held->host.low || live.host.high != held->host.high ||
        live.device != held->device || live.inode != held->inode ||
        live.revision != held->revision ||
        memcmp(live.name, held->name, sizeof(live.name)) ||
        memcmp(live.record.generation, held->record.generation, 16))
        return live.flags & MAPLESU_INSTALLATION_PENDING ? -EINPROGRESS : -ESTALE;
    error = msu_su_registry_non_root_policy(registry, mounts->full_uid, &policy);
    if (error) return error;
    return policy == MSU_SU_NON_ROOT_UMOUNT_ENABLED ? 0 : -ESTALE;
}

int msu_su_nonroot_after_setresuid(struct msu_su_registry *registry,
    u32 old_uid, long syscall_result, struct msu_su_nonroot_mounts *mounts,
    struct msu_su_nonroot_receipt *receipt)
{
    const struct cred *original, *saved;
    struct nsproxy *ns;
    struct path root, resolved, consumed;
    struct kstat stat;
    enum msu_su_non_root_umount_policy policy;
    unsigned long exec_id;
    u32 uid, manager_uid, manager_epoch, zygote_sid = 0, sid = 0, i;
    int error;
    if (!receipt) return -EINVAL;
    memset(receipt, 0, sizeof(*receipt));
    if (syscall_result < 0) return 0;
    if (syscall_result) { error = -EINVAL; goto validation; }
    if (!in_task() || in_atomic() || irqs_disabled()) {
        error = -EWOULDBLOCK; goto validation;
    }
    if (!msu_runtime_layout_ready()) { error = -EAGAIN; goto validation; }
    original = msu_dynamic_current_real_cred();
    if (!original || msu_dynamic_current_cred() != original || MSU_DYN_FIELD(original, cred, user_ns) != &init_user_ns) {
        error = -EPERM; goto validation;
    }
    uid = __kuid_val(MSU_DYN_FIELD(original, cred, uid));
    receipt->full_uid = uid;
    if (old_uid == uid || !application_uid(uid)) return 0;
    error = msu_su_registry_non_root_policy(registry, uid, &policy);
    if (error) goto validation;
    receipt->policy = policy;
    if (policy != MSU_SU_NON_ROOT_UMOUNT_ENABLED) return 0;

    /* Existing claimed Manager classification. Cold Manager exclusion must
     * additionally come from protected source membership, never !authorize. */
    error = msu_management_authorize(&manager_uid, &manager_epoch);
    if (!error) return 0;
    if (error != -EPERM) goto validation;
    if (!mounts) { error = -ENODATA; goto validation; }
    if (mounts->registry != registry || mounts->full_uid != uid) {
        error = -ESTALE; goto validation;
    }
    if (mounts->classification == MSU_SU_NONROOT_MANAGER) return 0;
    if (mounts->classification != MSU_SU_NONROOT_APP) {
        error = -EAGAIN; goto validation;
    }
    if (!mounts->live_generation || !mounts->generation ||
        (mounts->count && !mounts->entries)) { error = -EINVAL; goto validation; }
    ns = READ_ONCE(MSU_DYN_FIELD(current, task_struct, nsproxy));
    if (!ns || !MSU_DYN_FIELD(ns, nsproxy, mnt_ns) || MSU_DYN_FIELD(ns, nsproxy, mnt_ns) != mounts->mnt_ns ||
        !mounts->zygote_ns || !mounts->global_ns ||
        MSU_DYN_FIELD(ns, nsproxy, mnt_ns) == mounts->zygote_ns || MSU_DYN_FIELD(ns, nsproxy, mnt_ns) == mounts->global_ns ||
        MSU_DYN_FIELD(ns, nsproxy, mnt_ns) == MSU_DYN_FIELD(&init_nsproxy, nsproxy, mnt_ns) || !MSU_DYN_FIELD(current, task_struct, fs)) {
        error = -EXDEV; goto validation;
    }
    exec_id = READ_ONCE(MSU_DYN_FIELD(current, task_struct, self_exec_id));
    error = same_source(registry, mounts, original, ns, exec_id);
    if (error) goto validation;
    if (!mounts->count) { receipt->effects = MSU_SU_NONROOT_COMPLETED; return 0; }
    if (!MSU_SU_NONROOT_NATIVE_VERIFIED) { error = -EOPNOTSUPP; goto validation; }
    /* Same zygote domain check as fixed KSU, via public native security APIs.
     * No comm/PID/old_uid heuristic and no global SELinux policy mutation. */
    error = security_secctx_to_secid("u:r:zygote:s0", sizeof("u:r:zygote:s0") - 1, &zygote_sid);
    if (error) goto validation;
    security_cred_getsecid(original, &sid);
    if (!zygote_sid || sid != zygote_sid || !mounts->cred ||
        MSU_DYN_FIELD(mounts->cred, cred, user_ns) != &init_user_ns ||
        !cap_raised(MSU_DYN_FIELD(mounts->cred, cred, cap_effective), CAP_SYS_ADMIN)) {
        error = -EPERM; goto validation;
    }
    if (mounts->count > 1024U) { error = -E2BIG; goto validation; }
    /* All immutable metadata can be checked before propagation changes.
     * Hidden layers are resolved only after earlier layers have been removed. */
    for (i = 0; i < mounts->count; ++i) {
        struct msu_su_nonroot_mount *entry = &mounts->entries[i];
        receipt->index = i;
        if (!entry->name || entry->name[0] != '/' || !entry->name[1] ||
            entry->path.mnt || entry->path.dentry || !entry->inode ||
            (entry->flags != 0 && entry->flags != MNT_DETACH)) {
            error = -EINVAL; goto validation;
        }
    }
    msu_dynamic_get_fs_root(MSU_DYN_FIELD(current, task_struct, fs), &root);
    if (root.dentry != MSU_DYN_FIELD(root.mnt, vfsmount, mnt_root) || !our_mnt(root.mnt)) {
        path_put(&root); error = -EXDEV; goto validation;
    }
    error = same_source(registry, mounts, original, ns, exec_id);
    if (error) { path_put(&root); goto validation; }
    /* A different namespace alone does not exclude shared propagation. Only
     * explicit Enabled reaches this native operation, in the child's namespace. */
    saved = override_creds(mounts->cred);
    error = path_mount(NULL, &root, NULL, MS_PRIVATE | MS_REC, NULL);
    revert_creds(saved);
    path_put(&root);
    receipt->operation_error = error;
    if (!error) receipt->effects |= MSU_SU_NONROOT_PRIVATE_COMPLETED;
    receipt->validation_error = same_source(registry, mounts, original, ns, exec_id);
    if (error || receipt->validation_error) goto done;
    for (i = 0; i < mounts->count; ++i) {
        struct msu_su_nonroot_mount *entry = &mounts->entries[i];
        receipt->index = i;
        error = same_source(registry, mounts, original, ns, exec_id);
        if (error) goto validation;
        saved = override_creds(mounts->cred);
        error = kern_path(entry->name, LOOKUP_NO_SYMLINKS | LOOKUP_NO_MAGICLINKS, &resolved);
        if (!error) {
            error = vfs_getattr(&resolved, &stat, STATX_BASIC_STATS, AT_STATX_SYNC_AS_STAT);
            if (error) path_put(&resolved);
        }
        revert_creds(saved);
        if (error) { receipt->operation_error = error; goto done; }
        if (resolved.dentry != MSU_DYN_FIELD(resolved.mnt, vfsmount, mnt_root) || !our_mnt(resolved.mnt) ||
            stat.dev != entry->device || stat.ino != entry->inode ||
            MSU_DYN_FIELD(msu_dynamic_d_inode(resolved.dentry), inode, i_generation) != entry->inode_generation) {
            path_put(&resolved); error = -ESTALE; goto validation;
        }
        error = same_source(registry, mounts, original, ns, exec_id);
        if (error) { path_put(&resolved); goto validation; }
        entry->path = resolved;
        consumed = entry->path;
        memset(&entry->path, 0, sizeof(entry->path));
        ++receipt->attempted;
        saved = override_creds(mounts->cred);
        error = path_umount(&consumed, entry->flags); /* consumes on ALL results */
        revert_creds(saved);
        receipt->operation_error = error;
        if (!error) {
            ++receipt->unmounted;
            receipt->effects |= MSU_SU_NONROOT_UNMOUNTED;
        }
        receipt->validation_error = same_source(registry, mounts, original, ns, exec_id);
        if (error || receipt->validation_error) goto done;
    }
    receipt->effects |= MSU_SU_NONROOT_COMPLETED;
    return 0;
validation:
    receipt->validation_error = error;
done:
    receipt->error = receipt->operation_error ? receipt->operation_error : receipt->validation_error;
    return receipt->error;
}
