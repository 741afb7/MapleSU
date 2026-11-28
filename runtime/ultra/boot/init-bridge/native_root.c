// SPDX-License-Identifier: GPL-3.0-only
#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/kprobes.h>
#include <linux/mutex.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <asm/ptrace.h>
#include "manager_dynamic_layout.h"
#include "manager_credential_profile.h"
#include "native_lookup.h"

/* Same private group_info identity as the L3 carrier. The boot-pinned bridge
 * holds one reference; only an authenticated, unpublished ROOT cred receives
 * another through set_groups. fork/exec preserve it; setgroups replaces it. */
static struct group_info *msu_root_groups;
static DEFINE_MUTEX(msu_root_lock);
static struct kprobe msu_root_avc_probe;

static int msu_root_avc_pre(struct kprobe *probe, struct pt_regs *regs)
{
    const struct cred *cred;
    struct group_info *groups = smp_load_acquire(&msu_root_groups);

    (void)probe;
    if (!regs || !groups) return 0;
    cred = msu_dynamic_current_cred();
    if (!uid_eq(MSU_DYN_FIELD(cred, cred, uid), GLOBAL_ROOT_UID) ||
        !uid_eq(MSU_DYN_FIELD(cred, cred, euid), GLOBAL_ROOT_UID) ||
        MSU_DYN_FIELD(cred, cred, group_info) != groups) return 0;
    regs->regs[0] = 0;
    regs->pc = regs->regs[30];
    return 1;
}

static int msu_root_subject_start(void)
{
    struct group_info *groups;
    unsigned long entry;
    u32 first;
    int error;

    if (smp_load_acquire(&msu_root_groups)) return 0;
    mutex_lock(&msu_root_lock);
    if (msu_root_groups) { error = 0; goto unlock; }
    entry = msu_init_bridge_lookup("avc_denied");
    if (!entry) { error = -ENOENT; goto unlock; }
    memset(&msu_root_avc_probe, 0, sizeof(msu_root_avc_probe));
    msu_root_avc_probe.symbol_name = "avc_denied";
    msu_root_avc_probe.pre_handler = msu_root_avc_pre;
    error = register_kprobe(&msu_root_avc_probe);
    if (error) goto unlock;
    /* Return before the entry instruction executes. ARM64 may skip a BTI
     * landing pad, but no PAC or stack-changing instruction may precede us. */
    if ((unsigned long)msu_root_avc_probe.addr != entry &&
        ((unsigned long)msu_root_avc_probe.addr != entry + sizeof(first) ||
         copy_from_kernel_nofault(&first, (const void *)entry, sizeof(first)) ||
         first != 0xd503245fU)) {
        error = -EOPNOTSUPP;
        goto unregister;
    }
    groups = groups_alloc(0);
    if (!groups) { error = -ENOMEM; goto unregister; }
    smp_store_release(&msu_root_groups, groups);
    error = 0;
    goto unlock;
unregister:
    unregister_kprobe(&msu_root_avc_probe);
unlock:
    mutex_unlock(&msu_root_lock);
    return error;
}

int msu_native_root_prepare(struct cred *prepared)
{
    int error;

    if (!prepared || prepared == msu_dynamic_current_cred() ||
        prepared == msu_dynamic_current_real_cred() ||
        !uid_eq(MSU_DYN_FIELD(prepared, cred, uid), GLOBAL_ROOT_UID) ||
        !uid_eq(MSU_DYN_FIELD(prepared, cred, euid), GLOBAL_ROOT_UID)) return -EINVAL;
    error = msu_root_subject_start();
    if (!error) set_groups(prepared, smp_load_acquire(&msu_root_groups));
    return error;
}
