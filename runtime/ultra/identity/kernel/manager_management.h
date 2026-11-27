// SPDX-License-Identifier: GPL-3.0-only
#ifndef MAPLESU_MANAGER_MANAGEMENT_H
#define MAPLESU_MANAGER_MANAGEMENT_H

#include <linux/types.h>
#include "request_protocol.h"
#include "manager_early_fixed.h"

struct msu_manager_owner;

#if defined(MSU_MANAGEMENT_RESIDENT_TEXT_OWNER) && MSU_MANAGEMENT_RESIDENT_TEXT_OWNER
struct task_struct;
/* Real resident definition; it drains abandoned exact-task provider leases
 * while the management exit tracepoint is still registered. */
void maplesu_legacy_hook_provider_task_exit(struct task_struct *task);
#endif

#define MSU_RUNTIME_SERVICE_MAX_ENTRIES 64U

enum msu_runtime_service_kind {
    MSU_RUNTIME_SERVICE_UID_SU = 1,
    MSU_RUNTIME_SERVICE_EARLY_HELPER,
    MSU_RUNTIME_SERVICE_LEGACY_MODULE,
    MSU_RUNTIME_SERVICE_EARLY_FIXED,
};

struct msu_runtime_service_descriptor {
    maplesu_host_instance host;
    enum msu_runtime_service_kind kind;
    const void *lifecycle_owner;
    const u64 *entries;
    u32 entry_count;
};

/* A private, unpublished resident initializes once. No request initializes the
 * host. Published state and protection-failure quarantine live for this boot. */
int msu_management_init(struct msu_manager_owner *owner,
    const maplesu_host_instance *host);
/* Sleepable, serialized unpublished-host teardown. Closes new management
 * admission only after no session/service can still use these tables, then
 * calls the real UID host teardown without a management table, session, service
 * or publication lock. The discard-serialization mutex remains held; provider
 * callbacks never reenter host discard. Zero requires its controller/reader/
 * snapshot/RCU-work domain and mandatory real provider retire complete,
 * followed by actual tracepoint unregister/synchronization. PENDING or any
 * error keeps the host and all management state alive with admission closed;
 * only an explicit trusted follow-up may continue. A service-only RELEASED
 * receipt is not a UID/registry or external factory lifetime proof. The
 * factory must prevent future entry/context/provider calls and destroy its
 * actual registry before the UID teardown contract can report completion. */
int msu_management_discard_unpublished(void);

/* Producer intersects this real implementation/configuration mask with its
 * target import, ABI and execution-context qualification. Publication defaults
 * off: MSU_MANAGEMENT_PUBLICATION_GATE_VERIFIED=1 requires a frozen, qualified
 * P5 balanced gate builder; a compiler definition alone is not that proof.
 * MSU_MANAGEMENT_EXECMEM_ALLOC_RW=1 requires the actual target declaration.
 * CODE reserve uses module/execmem allocations, never a queried shared cursor;
 * an unsatisfied whole-reservation B26 anchor returns -ERANGE after cleanup.
 * SHARED_BOOL and UID_SNAPSHOT storage are independent of CODE/PUBLICATION
 * backend availability; storage never qualifies an installation/Profile.
 * PROCATTR implementation requires CONFIG_PROC_FS && CONFIG_SECURITY; the
 * producer must still qualify its actual native imports, fields and selector.
 * TASK_STATE defaults off; MSU_MANAGEMENT_TASK_STATE_VERIFIED=1 requires actual
 * target task/filter/entry-layout and native release KCFI qualification plus
 * proof that this gateway executes after the current seccomp evaluator. The
 * compiler definition alone supplies none of that evidence. */
u64 msu_management_capabilities(void);

/* No session: check the real current app's existing owner binding, never claim.
 * A stale session is retired and returns -ESTALE on this call. The principal
 * remains the original Manager UID after this session commits new credentials.
 * Atomic/IRQ-disabled contexts return -EWOULDBLOCK before session locking. */
int msu_management_authorize(u32 *principal_uid, u32 *epoch);
/* Same current-task protocol, with a bounded operation list for real init-user-
 * namespace root. Root may synchronize/read the existing durable factory mirror
 * (UID_COMMIT/READBACK handle=0), never create UID policy or control installation.
 * Stale sessions retain their failure instead of reopening through UID0. */
int msu_management_authorize_request(u32 command, u32 *principal_uid, u32 *epoch);
/* Dialect only, after authorize. Helper has its own installation-held session
 * and is never an APK Manager claim or an unrestricted mutation principal. */
bool msu_management_current_helper(void);
bool msu_management_helper_open_shape(const maplesu_request_owner_v3 *request,
    const maplesu_management_v3 *operation);
/* Lost-OPEN cleanup is scoped to this task's helper session; it never closes
 * an existing Manager session and an absent session is already closed. */
bool msu_management_helper_close_shape(const maplesu_request_owner_v3 *request,
    const maplesu_management_v3 *operation);

/* request/management are complete kernel snapshots. Only this module owns
 * session/resource IDs. Error responses retain completed mutation effects and
 * the confirmed WRITE count or full executed U64, independently of status.
 * TEXT WRITE accepts a four-byte-aligned address and a bounded byte length.
 * A partial final word preserves neighbors under text_mutex. Its result is
 * the readback-confirmed prefix in whole words, capped at the requested bytes.
 * TEXT_ATTEMPTED is set immediately before the native primitive;
 * WRITTEN records native success or a matching readback word. The original
 * native errno survives readback errors, and none of these imply quiescence.
 * An attempted TEXT error may have changed text beyond the confirmed prefix;
 * no error or lost completion authorizes mutation replay or resource release.
 * CLOSE session=0 retires this task's execution session, including when OPEN's
 * completion was lost; an absent session is an idempotent closed result.
 * SHARED_BOOL CREATE publishes one RW/NX byte and its ID only on success.
 * STORE accepts 0/1, performs one WRITE_ONCE then confirms by readback; a later
 * error retains WRITTEN. STORE/RELEASE return the input ID even on stale errors.
 * RELEASE drops only its creator reference and reports REFERENCE_DROPPED with
 * RELEASED or SHARED_RETAINED. CLOSE may report the same actual effects.
 * CRED_PROCATTR writes bounded native bytes only to this task's fixed default
 * current/exec procattr. PROC_WRITE_ATTEMPTED means vfs_write was called, not
 * that an LSM hook ran. result preserves a nonnegative native byte count even
 * on a later error; CRED_CHANGED records only the observed pointer change.
 * A full write with a valid same-task/mm/exec/owner lease may rotate active to
 * this controlled transaction's held after-image. All other credential checks
 * remain exact. Errors after attempt or any observed cred change retire the
 * session without rollback. Kernel does not promise a second content snapshot
 * or NUL validation; the actual native wrapper parses the supplied bytes.
 * TASK_STATE is a separate current-task seccomp transaction. COMPLETED records
 * an observed disabled state and CHANGED records this call's actual detach;
 * result is its original mode. Post-validation failure retains these facts,
 * and never changes the credential transaction receipt or authorizes replay.
 * Dispatch requires sleepable task context before taking any session mutex. */
void msu_management_dispatch(const maplesu_request_owner_v3 *request,
    const maplesu_management_v3 *management,
    maplesu_response_owner_v3 *response);

/* Process context after failed delivery. Zero selects this task's session.
 * It closes future management access; it does not undo committed credentials. */
void msu_management_delivery_failed(u64 session);

/* Trusted owner invalidation notification, immediately after publishing its
 * new epoch. Bounded, non-sleeping detach only; an in-flight reference retains
 * staging until its operation returns. Manager-owned published admission closes
 * atomically and retirement is queued; runtime execution uses its separate
 * trusted binding. This never claims or validates APKs. */
void msu_management_owner_invalidated(struct msu_manager_owner *owner);

/* Kernel-only P2/P7 producer interface, never a user role/Key/command. The real
 * registration callsite must prove its boot-live lifecycle owner and exact
 * entry/dependency factory; a non-NULL owner pointer is not authentication or
 * an acquired external-object reference. Owner/descriptor/output storage must
 * not overlap. All registration state and IDs are bounded, boot-stable and
 * never reused. Failed register clears its output without claiming a slot.
 * register/bind require sleepable task context; revoke rejects NMI and otherwise
 * only changes bounded table state and queues work. EARLY_HELPER is a task-only
 * service with entries=NULL/count=0; its actual HOST installation owner supplies
 * held executable/generation leases. Other service kinds require nonempty real
 * entry sets. Helper sessions retain service/pin refs through detached in-flight
 * completion; revoke reports PENDING until their final cleanup puts those refs.
 *
 * bind requires this task's current Manager session and a fresh, unexposed
 * PREPARE with complete uploads. The trusted producer must validate the real
 * gate/closure before bind; success freezes further member uploads. It checks
 * every member before assigning any domain, preserves creator refs, and never
 * upgrades an exposed publication. One service may own several exact entries
 * and share bools between its publications, never with another service.
 *
 * revoke first closes admission under the table lock and queues cleanup; it
 * never waits for execution refs or claims queued work has drained. Zero means
 * all its staging/exposed closures have actually retired; otherwise PENDING
 * and -EINPROGRESS survive, including quarantine. Repeating revoke queries and
 * retries actual retirement. Unrelated creator refs remain with their original
 * session. Manager invalidation still revokes every old mutation session, but
 * does not revoke an independently bound runtime service. No actual P2/P7
 * producer, target ABI/CFI or gate qualification is supplied by these APIs. */
int msu_runtime_service_register(const struct msu_runtime_service_descriptor *descriptor,
    u64 *service);
int msu_runtime_service_bind(const maplesu_host_instance *host, u64 service,
    u64 publication);
/* Only the actual current management validator context, with its operation
 * mutex already held. This never reacquires that mutex. No wire accepts it. */
int msu_runtime_service_bind_held(void *context, const maplesu_host_instance *host,
    u64 service, u64 publication);
int msu_runtime_service_revoke(const maplesu_host_instance *host, u64 service,
    u32 *effects);

/* Trusted kernel factory construction in the SAME bounded resource/publication
 * tables. host/service/owner must identify its real registered lifecycle owner;
 * these arguments are never read from a user request and do not authenticate a
 * user. The factory owns immutable kernel inputs and the actual qualified gate,
 * payload/fallback and full dependency closure. No gate bytes are generated here.
 *
 * These functions take service.operation, never a Manager session mutex, so a
 * real boot factory and a trusted callback already holding session.operation
 * use the same implementation. Lock order is session (if already held) ->
 * service -> publication -> CPU/text -> table; shared mutexes do not enter text.
 * Actual CODE/PUBLICATION capability qualification remains mandatory.
 *
 * Allocate then upload bounded kernel bytes. PREPARE snapshots a kernel
 * descriptor/member array (members_user must be zero), returns the existing
 * immutable control page and allows final gate uploads until first PUBLISH.
 * Native shared-bool creator refs transfer to the first prepared closure;
 * further closures share its own service ref, never another service's object.
 * Before the first native entry store, every registered entry must have one
 * complete prepared closure and every allocation/shared object must belong to
 * that closure set. This seals construction and forbids further uploads.
 * Effects/result/handle use the existing native receipt fields; first errors
 * and any actual effects survive. There is no new user command or protocol.
 * An allocation/upload/shared/PREPARE/PUBLISH failure ends this factory: consume
 * its receipt and abort the unexposed service before registering another one.
 * Canceled or empty failed-object tombstones are not retried into a success.
 * PUBLISH failure also closes service admission before returning, so a later
 * entry cannot expose a sealed construction whose earlier closure failed.
 *
 * abort_unexposed closes construction first, synchronizes queued cleanup, then
 * releases actual native resources/control/creator refs. Only a fully drained,
 * never-exposed service becomes a discarded tombstone; IDs are not reused.
 * Any possible exposure follows existing revoke/retire and reports PENDING,
 * retaining permanent gates. RELEASED covers only this service's owned table
 * objects, never UID controller/registry teardown or its RCU/work callbacks.
 * The real factory separately closes and drains those domains; host discard
 * checks the UID lifecycle even after service abort has completed.
 * Quarantine prevents host discard. A real factory
 * and its source/CFI/target qualification are still required by the caller. */
int msu_runtime_service_alloc(const maplesu_host_instance *host, u64 service,
    const void *owner, u32 bytes, bool code, u64 near_entry,
    maplesu_response_owner_v3 *response);
int msu_runtime_service_write(const maplesu_host_instance *host, u64 service,
    const void *owner, u64 handle, u32 offset, const void *bytes, u32 length,
    maplesu_response_owner_v3 *response);
int msu_runtime_service_shared_bool_create(const maplesu_host_instance *host,
    u64 service, const void *owner, u8 value, maplesu_response_owner_v3 *response);
/* Runtime-owned shared bool STORE. The service lock and provider owner are
 * checked before one WRITE_ONCE/readback; stale or revoked services never
 * turn a failed store into success. */
int msu_runtime_service_shared_bool_store(const maplesu_host_instance *host,
    u64 service, const void *owner, u64 id, u8 value,
    maplesu_response_owner_v3 *response);

/* Fixed EarlyHelper business provider registration and dispatch. */
int msu_early_fixed_provider_register(u32 operation_first, u32 operation_last,
    struct msu_early_fixed_provider *provider);
int msu_early_fixed_provider_unregister(u32 operation_first, u32 operation_last,
    const void *owner, u32 *effects);
int msu_early_fixed_provider_call(const maplesu_host_instance *host, u32 operation,
    u32 mode, u32 exploit, u64 value, struct msu_early_fixed_result *result);
int msu_early_fixed_provider_revoke(const maplesu_host_instance *host,
    const unsigned char generation[16], u32 *effects);
int msu_runtime_service_prepare(const maplesu_host_instance *host, u64 service,
    const void *owner, const maplesu_publication_prepare_v3 *descriptor,
    const maplesu_publication_member_v3 *members, maplesu_response_owner_v3 *response);
int msu_runtime_service_publish(const maplesu_host_instance *host, u64 service,
    const void *owner, u64 publication, maplesu_response_owner_v3 *response);
int msu_runtime_service_abort_unexposed(const maplesu_host_instance *host,
    u64 service, const void *owner, u32 *effects);

/* Trusted generated gate only. Payload is entered after this atomic
 * admission/ref operation; its task-owned record spans the complete original
 * and callback, including voluntary sleep, until the qualified gate releases it.
 * NMI enters select fallback. Calls must be balanced by the qualified emitter.
 * Aggregate refs detect zero underflow, not arbitrary duplicate nonzero leaves.
 * Unknown IDs do not change refs; a zero-underflow quarantines that publication.
 * Published closure outlives CLOSE/exec; owner revocation closes admission and
 * queues retirement for Manager-owned publications. Only a real trusted
 * runtime binding changes execution lifetime, never mutation qualification.
 * PREPARE pins module target text before reserving its closure; core text needs
 * no module lease. The pin remains through both gate grace periods and all
 * original/callback owners. IDs never qualify a user or another role.
 * PREPARE holds the closure in its original session and forbids member RELEASE
 * or EXEC. All reserved allocation pages reject public raw WRITE/TEXT; uploads
 * require the owning session's opaque handle, including while unpublished.
 * PUBLISH requires complete uploads, makes code RO/X and data RW/NX, and moves ownership
 * before possible entry exposure. DISABLE only closes admission and
 * restores an entry still owned by that publication. RETIRE first drains the
 * branch-before-admission window, waits for task-owned refs to reach zero, then
 * drains gate suffixes before freeing gate, payload, control and return pool.
 * Uncertainty retains quota with PENDING. The fixed 64-slot publication table
 * reuses only completely released and drained slots, with a fresh monotonic ID.
 * Each publication has 64 reusable return records; atomic overflow allocation
 * admits further calls only with a real record. Allocation failure closes new
 * admission, preserves existing owners, and retains the first error/status.
 * Only a fully retired, restored publication permits a new claim of its entry;
 * old-ID DISABLE/RETIRE returns its state or ESTALE after slot reuse without
 * touching the new hook.
 * SHARED_BOOL members acquire one ref per publication in the same host. A
 * temporary Owner bool keeps its Manager epoch; Legacy bools retain their
 * separate authenticated domain across Owner epoch changes. Equal numeric
 * epochs never permit either transport to acquire or mutate the other's bool.
 * A live runtime bool may be staged across epochs, but PUBLISH requires that
 * same trusted service binding. Each 24-byte member binds its exact ID and published byte address, while
 * ordinary members require address zero. Duplicate shared IDs within one
 * closure are rejected. Shared pages remain RW/NX.
 * They may only be read after successful enter and before leave, never in the
 * fallback or the branch-before-enter window. Unexposed cancellation
 * drops acquired pub refs; exposed retirement drops them after refs zero and
 * entry restoration. Creator CLOSE/exec/revoke drops only the creator ref.
 * Shared objects have 64 nonreused boot IDs/tombstones; final physical free and
 * STORE serialize on the object mutex. Temporary stale epochs reject STORE;
 * runtime STORE requires both the current legal Manager session and its live
 * service, without interpreting the object's creation epoch as a new lease.
 * no session can steal another session's still-attached creator reference.
 * STORE rechecks the current session/domain under the table lock before store;
 * invalidation overlapping an already started store preserves WRITTEN/error.
 * Sleepable order is session -> publication -> CPU hotplug -> text -> raw table;
 * shared refs use session -> publication (if any) -> one shared mutex -> table,
 * without entering CPU/text locks while holding the shared mutex.
 * enter/leave/revoke use only the raw table lock and queue sleepable cleanup. */
u64 msu_publication_enter(u64 publication);
void msu_publication_leave(u64 publication);

/* Shared credential transaction for trusted kernel consumers. NULL caps means
 * Root in init_user_ns with an empty supplementary group list. A non-NULL
 * validator proves the caller's own qualification before prepare and commit;
 * no user-supplied role, Key or callback crosses this interface. The caller
 * receives effects initialized to zero here and COMMITTED after commit. This
 * requires sleepable task context before validation/allocation. This changes
 * credentials only, not task/seccomp/LSM state or the Root environment. */
int msu_credential_replace_current(const maplesu_caps_v3 *caps,
    int (*validate)(void *context), void *context, u32 *effects);

enum msu_management_layout_word {
    MSU_MANAGEMENT_SCHEMA, MSU_MANAGEMENT_KERNEL_VERSION,
    MSU_MANAGEMENT_TASK_SIZE, MSU_MANAGEMENT_TASK_ALIGN,
    MSU_MANAGEMENT_TASK_CRED_OFFSET, MSU_MANAGEMENT_TASK_CRED_BYTES,
    MSU_MANAGEMENT_TASK_REAL_CRED_OFFSET, MSU_MANAGEMENT_TASK_REAL_CRED_BYTES,
    MSU_MANAGEMENT_TASK_MM_OFFSET, MSU_MANAGEMENT_TASK_MM_BYTES,
    MSU_MANAGEMENT_TASK_EXEC_OFFSET, MSU_MANAGEMENT_TASK_EXEC_BYTES,
    MSU_MANAGEMENT_TASK_FLAGS_OFFSET, MSU_MANAGEMENT_TASK_FLAGS_BYTES,
    MSU_MANAGEMENT_TASK_USAGE_OFFSET, MSU_MANAGEMENT_TASK_USAGE_BYTES,
    MSU_MANAGEMENT_CRED_SIZE, MSU_MANAGEMENT_CRED_ALIGN,
    MSU_MANAGEMENT_CRED_USAGE_OFFSET, MSU_MANAGEMENT_CRED_USAGE_BYTES,
    MSU_MANAGEMENT_CRED_NON_RCU_OFFSET, MSU_MANAGEMENT_CRED_NON_RCU_BYTES,
    MSU_MANAGEMENT_CRED_UID_OFFSET, MSU_MANAGEMENT_CRED_UID_BYTES,
    MSU_MANAGEMENT_CRED_GID_OFFSET, MSU_MANAGEMENT_CRED_GID_BYTES,
    MSU_MANAGEMENT_CRED_SUID_OFFSET, MSU_MANAGEMENT_CRED_SUID_BYTES,
    MSU_MANAGEMENT_CRED_SGID_OFFSET, MSU_MANAGEMENT_CRED_SGID_BYTES,
    MSU_MANAGEMENT_CRED_EUID_OFFSET, MSU_MANAGEMENT_CRED_EUID_BYTES,
    MSU_MANAGEMENT_CRED_EGID_OFFSET, MSU_MANAGEMENT_CRED_EGID_BYTES,
    MSU_MANAGEMENT_CRED_FSUID_OFFSET, MSU_MANAGEMENT_CRED_FSUID_BYTES,
    MSU_MANAGEMENT_CRED_FSGID_OFFSET, MSU_MANAGEMENT_CRED_FSGID_BYTES,
    MSU_MANAGEMENT_CRED_SECUREBITS_OFFSET, MSU_MANAGEMENT_CRED_SECUREBITS_BYTES,
    MSU_MANAGEMENT_CRED_INHERITABLE_OFFSET, MSU_MANAGEMENT_CRED_INHERITABLE_BYTES,
    MSU_MANAGEMENT_CRED_PERMITTED_OFFSET, MSU_MANAGEMENT_CRED_PERMITTED_BYTES,
    MSU_MANAGEMENT_CRED_EFFECTIVE_OFFSET, MSU_MANAGEMENT_CRED_EFFECTIVE_BYTES,
    MSU_MANAGEMENT_CRED_BOUNDING_OFFSET, MSU_MANAGEMENT_CRED_BOUNDING_BYTES,
    MSU_MANAGEMENT_CRED_AMBIENT_OFFSET, MSU_MANAGEMENT_CRED_AMBIENT_BYTES,
    MSU_MANAGEMENT_CRED_USER_OFFSET, MSU_MANAGEMENT_CRED_USER_BYTES,
    MSU_MANAGEMENT_CRED_USER_NS_OFFSET, MSU_MANAGEMENT_CRED_USER_NS_BYTES,
    MSU_MANAGEMENT_CRED_UCOUNTS_OFFSET, MSU_MANAGEMENT_CRED_UCOUNTS_BYTES,
    MSU_MANAGEMENT_CRED_GROUP_INFO_OFFSET, MSU_MANAGEMENT_CRED_GROUP_INFO_BYTES,
    MSU_MANAGEMENT_GROUP_INFO_SIZE, MSU_MANAGEMENT_GROUP_INFO_ALIGN,
    MSU_MANAGEMENT_GROUP_USAGE_OFFSET, MSU_MANAGEMENT_GROUP_USAGE_BYTES,
    MSU_MANAGEMENT_GROUP_USAGE_REFCOUNT,
    MSU_MANAGEMENT_MM_SIZE, MSU_MANAGEMENT_MM_ALIGN,
    MSU_MANAGEMENT_MM_COUNT_OFFSET, MSU_MANAGEMENT_MM_COUNT_BYTES,
    MSU_MANAGEMENT_MUTEX_SIZE, MSU_MANAGEMENT_MUTEX_ALIGN,
    MSU_MANAGEMENT_SPINLOCK_SIZE, MSU_MANAGEMENT_SPINLOCK_ALIGN,
    MSU_MANAGEMENT_SPINLOCK_RAW_OFFSET, MSU_MANAGEMENT_SPINLOCK_RAW_BYTES,
    MSU_MANAGEMENT_SPINLOCK_MAGIC_OFFSET, MSU_MANAGEMENT_SPINLOCK_MAGIC_BYTES,
    MSU_MANAGEMENT_SPINLOCK_CPU_OFFSET, MSU_MANAGEMENT_SPINLOCK_CPU_BYTES,
    MSU_MANAGEMENT_SPINLOCK_OWNER_OFFSET, MSU_MANAGEMENT_SPINLOCK_OWNER_BYTES,
    MSU_MANAGEMENT_SPINLOCK_DEP_MAP_OFFSET, MSU_MANAGEMENT_SPINLOCK_DEP_MAP_BYTES,
    MSU_MANAGEMENT_WORK_SIZE, MSU_MANAGEMENT_WORK_ALIGN,
    MSU_MANAGEMENT_WORK_DATA_OFFSET, MSU_MANAGEMENT_WORK_DATA_BYTES,
    MSU_MANAGEMENT_WORK_ENTRY_OFFSET, MSU_MANAGEMENT_WORK_ENTRY_BYTES,
    MSU_MANAGEMENT_WORK_FUNC_OFFSET, MSU_MANAGEMENT_WORK_FUNC_BYTES,
    MSU_MANAGEMENT_WORK_DEP_MAP_OFFSET, MSU_MANAGEMENT_WORK_DEP_MAP_BYTES,
    MSU_MANAGEMENT_CAP_SIZE, MSU_MANAGEMENT_CAP_ALIGN,
    MSU_MANAGEMENT_CAP_LAST, MSU_MANAGEMENT_PAGE_SHIFT,
    MSU_MANAGEMENT_TRACEPOINTS, MSU_MANAGEMENT_PREEMPT_RT, MSU_MANAGEMENT_MULTIUSER,
    MSU_MANAGEMENT_LAYOUT_WORDS,
};
extern const unsigned int
    msu_management_compiled_layout[MSU_MANAGEMENT_LAYOUT_WORDS];

/* Separate target evidence for the native procattr path; the original layout
 * words and their wire interpretation are unchanged. Configuration/declaration
 * shape is not proof of actual target import, selector, field layout or CFI. */
enum msu_procattr_layout_word {
    MSU_PROCATTR_SCHEMA, MSU_PROCATTR_KERNEL_VERSION, MSU_PROCATTR_CONFIGURED,
    MSU_PROCATTR_FILE_SIZE, MSU_PROCATTR_FILE_ALIGN,
    MSU_PROCATTR_FILE_OP_OFFSET, MSU_PROCATTR_FILE_OP_BYTES,
    MSU_PROCATTR_FILE_PATH_OFFSET, MSU_PROCATTR_FILE_PATH_BYTES,
    MSU_PROCATTR_FILE_INODE_OFFSET, MSU_PROCATTR_FILE_INODE_BYTES,
    MSU_PROCATTR_PATH_SIZE, MSU_PROCATTR_PATH_ALIGN,
    MSU_PROCATTR_PATH_DENTRY_OFFSET, MSU_PROCATTR_PATH_DENTRY_BYTES,
    MSU_PROCATTR_FILE_OPERATIONS_SIZE, MSU_PROCATTR_FILE_OPERATIONS_ALIGN,
    MSU_PROCATTR_WRITE_OFFSET, MSU_PROCATTR_WRITE_BYTES,
    MSU_PROCATTR_INODE_SIZE, MSU_PROCATTR_INODE_ALIGN,
    MSU_PROCATTR_INODE_SB_OFFSET, MSU_PROCATTR_INODE_SB_BYTES,
    MSU_PROCATTR_SB_SIZE, MSU_PROCATTR_SB_ALIGN,
    MSU_PROCATTR_SB_MAGIC_OFFSET, MSU_PROCATTR_SB_MAGIC_BYTES,
    MSU_PROCATTR_DENTRY_SIZE, MSU_PROCATTR_DENTRY_ALIGN,
    MSU_PROCATTR_DENTRY_PARENT_OFFSET, MSU_PROCATTR_DENTRY_PARENT_BYTES,
    MSU_PROCATTR_DENTRY_NAME_OFFSET, MSU_PROCATTR_DENTRY_NAME_BYTES,
    MSU_PROCATTR_QSTR_SIZE, MSU_PROCATTR_QSTR_ALIGN,
    MSU_PROCATTR_QSTR_LEN_OFFSET, MSU_PROCATTR_QSTR_LEN_BYTES,
    MSU_PROCATTR_QSTR_NAME_OFFSET, MSU_PROCATTR_QSTR_NAME_BYTES,
    MSU_PROCATTR_PROC_MAGIC, MSU_PROCATTR_LAYOUT_WORDS,
};
extern const unsigned int msu_procattr_compiled_layout[MSU_PROCATTR_LAYOUT_WORDS];

#endif
