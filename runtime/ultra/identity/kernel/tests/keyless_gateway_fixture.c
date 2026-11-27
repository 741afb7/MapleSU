// SPDX-License-Identifier: GPL-3.0-only
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "request_protocol.h"
#include "manager_resident_abi.h"

typedef uint32_t u32;
typedef uint64_t u64;
typedef uint16_t u16;
typedef uint8_t u8;
typedef int64_t s64;
#define ESTALE 116
#define __kuid_val(value) (value)
#define READ_ONCE(value) (value)
#define PF_EXITING 1
#define MSU_DYN_FIELD(value, type, field) ((value)->field)
#define smp_store_release(pointer, value) (*(pointer) = (value))
struct cred { u32 uid, euid; };
struct task_struct { void *mm; u64 self_exec_id; u32 flags; };
struct msu_runtime_service { bool live; unsigned kind; };
struct msu_installation_helper_lease { bool live; };
struct msu_manager_owner { bool bound; u32 uid, epoch; };
struct msu_management_session {
    struct task_struct *task;
    void *mm;
    u64 exec_id;
    const struct cred *active, *original;
    struct msu_installation_helper_lease *helper;
    struct msu_runtime_service *service;
    void *legacy_gateway;
    int operation;
    u32 epoch, principal_uid;
    bool attached, legacy_active, root;
};
enum { MSU_RUNTIME_SERVICE_EARLY_HELPER = 2 };
static struct task_struct task, other_task, *current = &task;
static struct cred app = {10123, 10123}, root_cred = {0, 0}, other_cred = {0, 0};
static const struct cred *active_cred = &app, *real_cred = &app;
static int init_user_ns, private_ns;
static void *user_ns = &init_user_ns;
static bool sleepable = true, layout_ready = true;
static struct msu_manager_owner owner;
static struct { bool ready; struct msu_manager_owner *owner; } management = {true, &owner};
static struct msu_management_session *current_session;
static unsigned retired;
static u32 msu_dynamic_current_uid(void) { return active_cred->uid; }
static u32 msu_dynamic_current_euid(void) { return active_cred->euid; }
static const struct cred *msu_dynamic_current_cred(void) { return active_cred; }
static const struct cred *msu_dynamic_current_real_cred(void) { return real_cred; }
static void *msu_dynamic_current_user_ns(void) { return user_ns; }
static bool msu_management_sleepable(void) { return sleepable; }
static bool msu_runtime_layout_ready(void) { return layout_ready; }
static struct msu_management_session *msu_management_get(u64 id)
{ (void)id; return current_session; }
static void msu_management_put(struct msu_management_session *session) { (void)session; }
static void mutex_lock(int *lock) { (void)lock; }
static void mutex_unlock(int *lock) { (void)lock; }
static bool mutex_trylock(int *lock) { (void)lock; return true; }
static void msu_management_retire(struct msu_management_session *session)
{ session->attached = false; ++retired; }
static bool msu_runtime_service_live(struct msu_runtime_service *service) { return service->live; }
static int msu_installation_helper_validate(struct msu_installation_helper_lease *lease)
{ return lease->live ? 0 : -ESTALE; }
static u32 msu_manager_owner_epoch(struct msu_manager_owner *value) { return value->epoch; }
static bool msu_manager_owner_allowed(struct msu_manager_owner *value, u32 uid, u32 *epoch)
{
    *epoch = 0;
    if (!value->bound || uid != value->uid || uid != active_cred->uid) return false;
    *epoch = value->epoch;
    return true;
}
static const struct cred *override_creds(const struct cred *next)
{ const struct cred *old = active_cred; active_cred = next; return old; }
static void revert_creds(const struct cred *old) { active_cred = old; }
#include "gateway.inc"

static int msu_resident_discard_mutex;
static unsigned msu_resident_discard_stage, retirement_calls, installation_calls;
static bool msu_resident_sleepable(void) { return sleepable; }
static int msu_resident_retirement_prepare(const struct msu_resident_config_v3 *config)
{
    ++retirement_calls;
    return config->slot && config->anchor && config->memory && config->allocation_bytes &&
        config->retire_work ? 0 : -EINVAL;
}
static int msu_manager_owner_init(struct msu_manager_owner *value)
{ value->epoch = 1; return 0; }
static void get_random_bytes(void *output, size_t bytes) { memset(output, 0x5a, bytes); }
static int msu_management_init(struct msu_manager_owner *value, const maplesu_host_instance *instance)
{ (void)value; return (instance->low | instance->high) ? 0 : -EINVAL; }
static u64 msu_management_capabilities(void) { return MAPLESU_OWNER_CAPABILITIES; }
static int msu_installation_init(const maplesu_host_instance *instance, const u8 name[16])
{ (void)instance; (void)name; ++installation_calls; return 0; }
static int msu_su_runtime_init(const maplesu_host_instance *instance,
    const struct msu_deployment_namespace_record *deployment)
{ (void)instance; return msu_deployment_namespace_valid(deployment) ? 0 : -EINVAL; }
#include "resident.inc"

static void reset_authorization(void)
{
    task = (struct task_struct){.mm = &task, .self_exec_id = 5};
    current = &task;
    active_cred = real_cred = &app;
    user_ns = &init_user_ns;
    owner = (struct msu_manager_owner){.epoch = 7};
    management.ready = sleepable = layout_ready = true;
    current_session = NULL;
    retired = 0;
}
static void check_gateway(void)
{
    u32 uid, epoch;
    reset_authorization();
    assert(msu_management_authorize_request(MAPLESU_REQUEST_QUERY, &uid, &epoch) == -EPERM);
    owner.bound = true; owner.uid = app.uid;
    assert(!msu_management_authorize_request(MAPLESU_REQUEST_UID_COMMIT, &uid, &epoch));
    assert(uid == app.uid && epoch == 7);
    active_cred = real_cred = &root_cred;
    assert(msu_management_authorize(&uid, &epoch) == -EPERM);
    assert(!msu_management_authorize_request(MAPLESU_REQUEST_QUERY, &uid, &epoch));
    assert(uid == 0 && epoch == 1 && owner.uid == app.uid);
    for (u32 command = MAPLESU_REQUEST_UID_CREATE; command <= MAPLESU_REQUEST_UID_SHUTDOWN; ++command) {
        const int expected = command == MAPLESU_REQUEST_UID_COMMIT ||
            command == MAPLESU_REQUEST_UID_READBACK ? 0 : -EPERM;
        assert(msu_management_authorize_request(command, &uid, &epoch) == expected);
    }
    assert(msu_management_authorize_request(MAPLESU_REQUEST_INSTALLATION_STATE, &uid, &epoch) == -EPERM);
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EARLY_FIXED, &uid, &epoch) == -EPERM);
    maplesu_management_v3 operation = {0};
    assert(msu_management_root_operation(MAPLESU_REQUEST_UID_COMMIT, &operation));
    assert(msu_management_root_operation(MAPLESU_REQUEST_UID_READBACK, &operation));
    operation.handle = 1;
    assert(!msu_management_root_operation(MAPLESU_REQUEST_UID_COMMIT, &operation));
    assert(!msu_management_root_operation(MAPLESU_REQUEST_UID_READBACK, &operation));
    assert(!msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch));
    user_ns = &private_ns;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -EPERM);
    user_ns = &init_user_ns; real_cred = &other_cred;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -EPERM);
    real_cred = &root_cred;
    sleepable = false;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -EWOULDBLOCK);
    puts("current-root-operation-admission: passed");

    reset_authorization(); active_cred = real_cred = &root_cred;
    struct msu_management_session session = {.task = current, .mm = current->mm,
        .exec_id = 5, .active = &root_cred, .original = &root_cred,
        .epoch = 1, .attached = true, .root = true};
    current_session = &session;
    assert(!msu_management_authorize_request(MAPLESU_REQUEST_WRITE, &uid, &epoch));
    owner.epoch++;
    assert(!msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch));
    assert(msu_management_authorize_request(MAPLESU_REQUEST_UID_CREATE, &uid, &epoch) == -EPERM);
    current->self_exec_id++;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -ESTALE);
    assert(retired == 1 && !session.attached);
    session.attached = true; session.exec_id = current->self_exec_id; current = &other_task;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -ESTALE);
    current = &task; session.attached = true; real_cred = &other_cred;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -ESTALE);
    real_cred = &root_cred; session.attached = true; session.legacy_gateway = &session;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_EXEC, &uid, &epoch) == -EPERM);
    puts("root-exact-session-lifetime: passed");

    reset_authorization(); owner.bound = true; owner.uid = app.uid;
    session = (struct msu_management_session){.task = current, .mm = current->mm,
        .exec_id = 5, .active = &app, .original = &app, .principal_uid = app.uid,
        .epoch = 7, .attached = true};
    current_session = &session;
    assert(!msu_management_authorize_request(MAPLESU_REQUEST_UID_READBACK, &uid, &epoch));
    owner.epoch++;
    assert(msu_management_authorize_request(MAPLESU_REQUEST_UID_READBACK, &uid, &epoch) == -ESTALE);
    puts("owner-revocation-preserved: passed");
}
static void retire_callback(struct work_struct *work) { (void)work; }
static void check_resident(void)
{
    u8 append[MAPLESU_LEGACY_METADATA_V2_BYTES] = {0}, image[4] = {0};
    struct msu_resident_config config = {.version = 1, .append_bytes = sizeof append,
        .image = image, .append = append, .credential = NULL};
    memset(&host, 0, sizeof host);
    assert(!msu_manager_resident_init(&config) && host.initialized);
    assert(msu_manager_resident_init(&config) == -EINVAL);
    struct msu_deployment_namespace_record deployment = {.magic = MSU_DEPLOYMENT_NAMESPACE_MAGIC,
        .schema = MSU_DEPLOYMENT_NAMESPACE_SCHEMA, .bytes = sizeof deployment};
    memcpy(deployment.name, "PublicNamespace1", sizeof deployment.name);
    struct msu_resident_config_v2 config2 = {.version = 2, .config_bytes = sizeof config2,
        .append_bytes = sizeof append, .namespace_record_bytes = sizeof deployment,
        .image = image, .append = append, .credential = NULL, .namespace_record = &deployment};
    memset(&host, 0, sizeof host);
    assert(!msu_manager_resident_init((const void *)&config2));
    assert(host.deployment_available && installation_calls == 1);
    memset(&host, 0, sizeof host); config2.config_bytes--;
    assert(msu_manager_resident_init((const void *)&config2) == -EINVAL);
    config2.config_bytes++; deployment.name[0] = '/';
    assert(msu_manager_resident_init((const void *)&config2) == -EINVAL);
    deployment.name[0] = 'P';
    struct msu_resident_slot slot = {0};
    struct msu_resident_config_v3 config3 = {.base = config2,
        .anchor = (const void *)image, .slot = &slot, .memory = image,
        .allocation_bytes = 4096, .retire_work = retire_callback};
    config3.base.version = 3; config3.base.config_bytes = sizeof config3;
    assert(!msu_manager_resident_init((const void *)&config3));
    assert(retirement_calls == 1);
    memset(&host, 0, sizeof host); config3.memory = NULL;
    assert(msu_manager_resident_init((const void *)&config3) == -EINVAL);
    assert(retirement_calls == 2);
    msu_resident_discard_stage = 2;
    assert(msu_manager_resident_init(&config) == -EINPROGRESS);
    puts("keyless-resident-layout-and-admission: passed");
}
int main(void) { check_gateway(); check_resident(); return 0; }
