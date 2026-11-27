// SPDX-License-Identifier: GPL-3.0-only
#include <linux/errno.h>
#include <linux/string.h>
#include "su_install_registry.h"
#include "su_profile_apply.h"
#include "su_profile_execution.h"
#include "manager_dynamic_layout.h"
#include "manager_task_state.h"

int msu_su_profile_check_execution(const struct msu_su_profile *profile)
{
    int error;
    /* CORE/SU task cleanup uses its qualified native path, independently of
     * the optional management TASK_STATE command. */
    if (!msu_runtime_layout_ready()) return -EPROTO;
    error = msu_su_profile_check(profile);
    if (error) return error;
    return msu_su_profile_namespace_supported(profile->namespace_mode);
}

static int msu_su_apply_held_stages(struct msu_su_lease *lease,
    struct msu_su_execution_receipt *receipt)
{
    int error;
    receipt->phase = MSU_SU_EXECUTION_SUPPORT;
    error = msu_su_profile_check_execution(msu_su_lease_profile(lease));
    if (error) goto done;
    receipt->phase = MSU_SU_EXECUTION_CREDENTIALS;
    error = msu_su_profile_apply_held(lease, &receipt->credential_effects);
    if (error) goto done;
    receipt->phase = MSU_SU_EXECUTION_TASK;
    error = msu_task_state_release_current(msu_su_lease_validate, lease,
        &receipt->task_effects, &receipt->previous_seccomp_mode);
    if (error) goto done;
    receipt->phase = MSU_SU_EXECUTION_PROFILE_FLAGS;
    error = msu_task_profile_apply_current(msu_su_lease_profile(lease)->flags,
        msu_su_lease_validate, lease, &receipt->task_profile);
    if (error) goto done;
    receipt->phase = MSU_SU_EXECUTION_NAMESPACE;
    error = msu_su_profile_namespace_apply_held(lease, &receipt->mount_namespace);
    if (error) goto done;
    receipt->phase = MSU_SU_EXECUTION_FINAL_VALIDATE;
    error = msu_su_lease_validate(lease);
    if (!error) receipt->completed = true;
done:
    receipt->error = error;
    return error;
}

int msu_su_apply_held_detailed(struct msu_su_lease *lease,
    struct msu_su_execution_receipt *receipt)
{
    int error;
    if (!receipt) return -EINVAL;
    memset(receipt, 0, sizeof(*receipt));
    receipt->phase = MSU_SU_EXECUTION_ADMISSION;
    error = msu_su_lease_validate(lease);
    if (!error) error = msu_su_apply_held_stages(lease, receipt);
    receipt->error = error;
    return error;
}

int msu_su_apply_current_detailed(const void *context,
    struct msu_su_execution_receipt *receipt)
{
    struct msu_su_lease lease = {0};
    int error;
    if (!receipt) return -EINVAL;
    memset(receipt, 0, sizeof(*receipt));
    receipt->phase = MSU_SU_EXECUTION_ADMISSION;
    error = msu_su_lease_begin(context, &lease);
    if (error) goto done;
    error = msu_su_apply_held_stages(&lease, receipt);
    msu_su_lease_end(&lease);
done:
    receipt->error = error;
    return error;
}
