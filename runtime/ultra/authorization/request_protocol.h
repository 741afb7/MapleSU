#ifndef MAPLESU_REQUEST_PROTOCOL_H
#define MAPLESU_REQUEST_PROTOCOL_H

#ifdef __KERNEL__
#include <linux/stddef.h>
#include <linux/types.h>
#ifndef UINT32_C
#define UINT32_C(value) value##U
#define UINT64_C(value) value##ULL
#endif
#else
#include <stddef.h>
#include <stdint.h>
#endif

#define MAPLESU_REQUEST_SELECTOR_FLAG 52u
#define MAPLESU_REQUEST_SELECTOR_SUB 0xa2u
#define MAPLESU_REQUEST_PROTOCOL_VERSION 2u
#define MAPLESU_REQUEST_WIRE_BYTES 48u
#define MAPLESU_RESPONSE_WIRE_BYTES 48u
#define MAPLESU_METADATA_WIRE_BYTES 48u
#define MAPLESU_METADATA_VERSION 2u
/* Version 2 publishes the legacy append fields through seccomp, excluding
 * the four-byte RET that precedes the append data in its producer record. */
#define MAPLESU_LEGACY_METADATA_V2_BYTES 54u
#define MAPLESU_LEGACY_METADATA_AUDIT_BYTES 58u
/* Negotiated extension. The old u32 append/veneer fields are zero sentinels,
 * never truncated addresses. Signed 64-bit entries retain the real _text. */
#define MAPLESU_APPEND64_BYTES 88u
#define MAPLESU_APPEND64_MAGIC UINT32_C(0x3436534d)
#define MAPLESU_APPEND64_HEADER 58u
#define MAPLESU_APPEND64_BASE 64u
#define MAPLESU_APPEND64_LOOKUP 72u
#define MAPLESU_APPEND64_AVC 80u
#define MAPLESU_CAP_APPEND64 UINT64_C(262144)
#define MAPLESU_REQUEST_MAGIC UINT32_C(0x3251534d)
#define MAPLESU_RESPONSE_MAGIC UINT32_C(0x3252534d)
#define MAPLESU_METADATA_MAGIC UINT32_C(0x324d534d)
#define MAPLESU_REQUEST_QUERY 1u
#define MAPLESU_REQUEST_ECHO64 2u
#define MAPLESU_REQUEST_READ 3u
#define MAPLESU_CAP_QUERY UINT64_C(1)
#define MAPLESU_CAP_ECHO64 UINT64_C(2)
#define MAPLESU_CAP_READ UINT64_C(4)
/* This producer also publishes the four-byte audit offset at append + 54. */
#define MAPLESU_CAP_APPEND_AUDIT UINT64_C(16)
#define MAPLESU_REQUEST_CAPABILITIES (MAPLESU_CAP_QUERY | MAPLESU_CAP_ECHO64 | MAPLESU_CAP_READ)
#define MAPLESU_READ_MAX_BYTES 256u
#define MAPLESU_REQUEST_STATUS_OK 0
#define MAPLESU_REQUEST_STATUS_INVALID (-22)
#define MAPLESU_REQUEST_STATUS_FAULT (-14)
#define MAPLESU_REQUEST_STATUS_UNSUPPORTED (-95)

/* Current-task dialect of the same envelope/selector. This public prefix
 * identifies a frame and grants no authority. After full v3 validation, QUERY
 * binds the real APK Manager or an already-root task with its precise lifetime.
 * READ/ECHO require that binding. Every operation revalidates current identity
 * and its permitted operation; a rejection never enters another dialect. */
#define MAPLESU_OWNER_ENVELOPE_PREFIX "MapleSU:owner:v3:UID:epoch:host:0000000000000000"
#define MAPLESU_OWNER_ENVELOPE_PREFIX_BYTES 48u
#define MAPLESU_OWNER_PROTOCOL_VERSION 3u
#define MAPLESU_OWNER_METADATA_VERSION 3u
#define MAPLESU_OWNER_METADATA_WIRE_BYTES 80u
#define MAPLESU_OWNER_READ_WIRE_BYTES 32u
#define MAPLESU_OWNER_SCHEMA 1u
#define MAPLESU_OWNER_STATE_BOUND 1u
/* Only an authenticated native helper OPEN returns this state. It is not a
 * Manager claim and is never published by any Manager QUERY descriptor. */
#define MAPLESU_OWNER_STATE_HELPER 2u

/* Management uses the same current-task dialect. OPEN authenticates an existing
 * Manager/Root binding or the native helper through its restricted service flow.
 * Root is a separate principal and gains no Manager qualification. Each later
 * operation binds session, host, epoch and current identity with its own scope;
 * unknown or uncertain operations are never replayed. */
#define MAPLESU_REQUEST_SESSION_OPEN 4u
#define MAPLESU_REQUEST_SESSION_CLOSE 5u
#define MAPLESU_REQUEST_ALLOC 6u
#define MAPLESU_REQUEST_WRITE 7u
#define MAPLESU_REQUEST_EXEC 8u
#define MAPLESU_REQUEST_RELEASE 9u
#define MAPLESU_REQUEST_CRED_ROOT 10u
#define MAPLESU_REQUEST_CRED_CAPS 11u
#define MAPLESU_REQUEST_PUBLICATION_PREPARE 12u
#define MAPLESU_REQUEST_PUBLICATION_PUBLISH 13u
#define MAPLESU_REQUEST_PUBLICATION_DISABLE 14u
#define MAPLESU_REQUEST_PUBLICATION_RETIRE 15u
#define MAPLESU_REQUEST_SHARED_BOOL_CREATE 16u
#define MAPLESU_REQUEST_SHARED_BOOL_STORE 17u
#define MAPLESU_REQUEST_SHARED_BOOL_RELEASE 18u
#define MAPLESU_REQUEST_UID_CREATE 19u
#define MAPLESU_REQUEST_UID_PREPARE 20u
#define MAPLESU_REQUEST_UID_COMMIT 21u
#define MAPLESU_REQUEST_UID_CANCEL 22u
#define MAPLESU_REQUEST_UID_READBACK 23u
#define MAPLESU_REQUEST_UID_SHUTDOWN 24u
#define MAPLESU_REQUEST_CRED_PROCATTR 25u
#define MAPLESU_REQUEST_TASK_STATE 26u
/* Optional authenticated read on the existing management/session envelope.
 * Old hosts explicitly return UNSUPPORTED; no QUERY/capability view changes. */
#define MAPLESU_REQUEST_DEPLOYMENT_READ 27u
/* Installation facts use the same R02 selector and explicit owner dialect.
 * They never advertise a new QUERY view or qualify SU/Ready. */
#define MAPLESU_REQUEST_INSTALLATION_STATE 28u
#define MAPLESU_REQUEST_VENDOR_STATE 29u
/* Read actual boot-owned SU factory facts; never installation/namespace Ready. */
#define MAPLESU_REQUEST_SU_RUNTIME_READ 30u
/* Private fixed EarlyHelper business transport. It is not advertised by QUERY. */
#define MAPLESU_REQUEST_EARLY_FIXED 31u
#define MAPLESU_EARLY_FIXED_OEM_MTK 1u
#define MAPLESU_EARLY_FIXED_OEM_OPLUS_GUARD 2u
#define MAPLESU_EARLY_FIXED_OEM_OPLUS_INTE 3u
#define MAPLESU_EARLY_FIXED_ROOT_ENV 4u
#define MAPLESU_EARLY_FIXED_SELINUX_PREPARE 5u
#define MAPLESU_EARLY_FIXED_SELINUX_ENABLE 6u
#define MAPLESU_EARLY_FIXED_SELINUX_READ_STATUS 7u
#define MAPLESU_EARLY_FIXED_SELINUX_WRITE_SEQUENCE 8u
#define MAPLESU_EARLY_FIXED_MODE_MAX 3u
#define MAPLESU_EARLY_FIXED_EXPLOIT_MAX 31u
/* Command-31 result effects use one operation-aware namespace. The first six
 * facts are common; ROOT and SELINUX facts occupy disjoint, typed ranges. */
#define MAPLESU_EARLY_FIXED_EFFECT_ATTEMPTED       (1u << 0)
#define MAPLESU_EARLY_FIXED_EFFECT_APPLIED         (1u << 1)
#define MAPLESU_EARLY_FIXED_EFFECT_READBACK        (1u << 2)
#define MAPLESU_EARLY_FIXED_EFFECT_PENDING         (1u << 3)
#define MAPLESU_EARLY_FIXED_EFFECT_RELEASED        (1u << 4)
#define MAPLESU_EARLY_FIXED_EFFECT_QUARANTINED     (1u << 5)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_PRECHECK   (1u << 6)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_AVC        (1u << 7)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_FILLDIR    (1u << 8)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_COMPAT     (1u << 9)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_HBT        (1u << 10)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_ITERATE    (1u << 11)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_AUDIT      (1u << 12)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_REVOKE     (1u << 13)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_PENDING    (1u << 14)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_QUARANTINED (1u << 15)
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_CLOSED     (1u << 16)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_ALLOCATED (1u << 17)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_RELEASED (1u << 18)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_EXECUTED (1u << 19)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_COMMITTED (1u << 20)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_QUARANTINED (1u << 21)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_WRITTEN (1u << 22)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_TEXT_ATTEMPTED (1u << 23)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_PUBLICATION_PREPARED (1u << 24)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_PUBLICATION_PUBLISHED (1u << 25)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_PUBLICATION_RETAINED (1u << 26)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_GATE_PINNED (1u << 27)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_PENDING (1u << 28)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_REFERENCE_DROPPED (1u << 29)
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_SHARED_RETAINED (1u << 30)
#define MAPLESU_EARLY_FIXED_EFFECT_COMMON_MASK 0x0000003fu
#define MAPLESU_EARLY_FIXED_EFFECT_ROOT_MASK 0x0001ffffu
#define MAPLESU_EARLY_FIXED_EFFECT_SELINUX_MASK 0x7ffe003fu

#define MAPLESU_SU_RUNTIME_STATUS_SCHEMA 1u
#define MAPLESU_SU_RUNTIME_MAX_HOOKS 12u
#define MAPLESU_SU_RUNTIME_INITIALIZED 1u
#define MAPLESU_SU_RUNTIME_BOUND 2u
#define MAPLESU_SU_RUNTIME_STARTED 4u
#define MAPLESU_SU_RUNTIME_PUBLISHED 8u
#define MAPLESU_SU_RUNTIME_CLOSED 16u
#define MAPLESU_SU_RUNTIME_INSTALLATION_CLOSED 32u
#define MAPLESU_INSTALLATION_READ 1u
#define MAPLESU_INSTALLATION_COMMIT 2u
#define MAPLESU_INSTALLATION_REVOKE 3u
#define MAPLESU_VENDOR_BEGIN 4u
#define MAPLESU_VENDOR_BEFORE 5u
#define MAPLESU_VENDOR_COMPLETE 6u
#define MAPLESU_INSTALLATION_STATE_MAGIC UINT32_C(0x4153494d)
#define MAPLESU_INSTALLATION_STATE_SCHEMA 1u
#define MAPLESU_INSTALLATION_PENDING 1u
#define MAPLESU_DEPLOYMENT_NAMESPACE_MAGIC UINT32_C(0x4e44534d)
#define MAPLESU_DEPLOYMENT_NAMESPACE_SCHEMA 1u
#define MAPLESU_DEPLOYMENT_NAMESPACE_BYTES 16u
#define MAPLESU_DEPLOYMENT_NAMESPACE_WIRE_BYTES 24u
#define MAPLESU_CAP_SESSION UINT64_C(32)
#define MAPLESU_CAP_ALLOC UINT64_C(64)
#define MAPLESU_CAP_WRITE UINT64_C(128)
#define MAPLESU_CAP_EXEC UINT64_C(256)
#define MAPLESU_CAP_RELEASE UINT64_C(512)
#define MAPLESU_CAP_CREDENTIAL UINT64_C(1024)
#define MAPLESU_CAP_TEXT_WRITE UINT64_C(2048)
#define MAPLESU_CAP_CODE_RESERVE UINT64_C(4096)
#define MAPLESU_CAP_PUBLICATION UINT64_C(8192)
#define MAPLESU_CAP_SHARED_BOOL UINT64_C(16384)
/* Typed snapshot storage only: no installation or SU/Profile qualification. */
#define MAPLESU_CAP_UID_SNAPSHOT UINT64_C(32768)
#define MAPLESU_CAP_PROCATTR UINT64_C(65536)
#define MAPLESU_CAP_TASK_STATE UINT64_C(131072)
#define MAPLESU_PROCATTR_CURRENT 1u
#define MAPLESU_PROCATTR_EXEC 2u
#define MAPLESU_PROCATTR_MAX_BYTES 4096u
#define MAPLESU_UID_SNAPSHOT_SCHEMA 1u
#define MAPLESU_UID_SNAPSHOT_MAX_RECORDS 4095u
#define MAPLESU_MANAGEMENT_CAPABILITIES (MAPLESU_CAP_SESSION | MAPLESU_CAP_ALLOC | MAPLESU_CAP_WRITE | MAPLESU_CAP_EXEC | MAPLESU_CAP_RELEASE | MAPLESU_CAP_CREDENTIAL)
#define MAPLESU_HELPER_BASE_CAPABILITIES (MAPLESU_CAP_ECHO64 | MAPLESU_CAP_READ | MAPLESU_CAP_SESSION | MAPLESU_CAP_CREDENTIAL)
#define MAPLESU_HELPER_OPTIONAL_CAPABILITIES (MAPLESU_CAP_PROCATTR | MAPLESU_CAP_TASK_STATE)
/* QUERY arg0=0 retains the original v3 capability/descriptor view. A client may
 * request exactly this optional mask; an explicit zero-output INVALID or
 * UNSUPPORTED response permits one same-v3 base QUERY, never an unknown retry. */
#define MAPLESU_OWNER_BASE_CAPABILITIES (MAPLESU_REQUEST_CAPABILITIES | MAPLESU_MANAGEMENT_CAPABILITIES)
#define MAPLESU_OWNER_OPTIONAL_CAPABILITIES (MAPLESU_CAP_TEXT_WRITE | MAPLESU_CAP_CODE_RESERVE | MAPLESU_CAP_PUBLICATION | MAPLESU_CAP_SHARED_BOOL)
#define MAPLESU_OWNER_UID_QUERY (MAPLESU_OWNER_OPTIONAL_CAPABILITIES | MAPLESU_CAP_UID_SNAPSHOT)
#define MAPLESU_OWNER_PROCATTR_QUERY (MAPLESU_OWNER_UID_QUERY | MAPLESU_CAP_PROCATTR)
#define MAPLESU_OWNER_TASK_QUERY (MAPLESU_OWNER_PROCATTR_QUERY | MAPLESU_CAP_TASK_STATE)
#define MAPLESU_OWNER_CAPABILITIES (MAPLESU_OWNER_BASE_CAPABILITIES | MAPLESU_OWNER_OPTIONAL_CAPABILITIES | MAPLESU_CAP_UID_SNAPSHOT | MAPLESU_CAP_PROCATTR | MAPLESU_CAP_TASK_STATE)
/* Each selector has its own immutable descriptor view. The original optional
 * view never advertises UID_SNAPSHOT to clients whose known mask predates it.
 * Newer views add UID, procattr and task-state capabilities in order. Clients
 * may try an older view only after a complete zero-output rejection. */
#define MAPLESU_MANAGEMENT_MAX_BYTES (1024u * 1024u)
#define MAPLESU_MANAGEMENT_WRITE_TEXT 1u
#define MAPLESU_MANAGEMENT_ALLOC_CODE 1u
#define MAPLESU_PUBLICATION_GATE_CODE 1u
#define MAPLESU_PUBLICATION_GATE_DATA 2u
#define MAPLESU_PUBLICATION_PAYLOAD_CODE 3u
#define MAPLESU_PUBLICATION_PAYLOAD_DATA 4u
/* Shared bool handles use a separate object namespace, never ordinary ALLOC. */
#define MAPLESU_PUBLICATION_SHARED_BOOL 5u
#define MAPLESU_EFFECT_ALLOCATED 1u
#define MAPLESU_EFFECT_RELEASED 2u
#define MAPLESU_EFFECT_EXECUTED 4u
#define MAPLESU_EFFECT_COMMITTED 8u
#define MAPLESU_EFFECT_SESSION_CLOSED 16u
#define MAPLESU_EFFECT_QUARANTINED 32u
#define MAPLESU_EFFECT_WRITTEN 64u
#define MAPLESU_EFFECT_TEXT_ATTEMPTED 128u
#define MAPLESU_EFFECT_PUBLICATION_PREPARED 256u
#define MAPLESU_EFFECT_PUBLICATION_PUBLISHED 512u
#define MAPLESU_EFFECT_PUBLICATION_RETAINED 1024u
#define MAPLESU_EFFECT_GATE_PINNED 2048u
#define MAPLESU_EFFECT_PENDING 4096u
#define MAPLESU_EFFECT_REFERENCE_DROPPED 8192u
#define MAPLESU_EFFECT_SHARED_RETAINED 16384u
/* Facts from this command, not proof that a named LSM hook committed. The
 * native VFS call may fail before the proc writer/LSM; credential-pointer
 * change is observed independently. Neither permits mutation replay. */
#define MAPLESU_EFFECT_PROC_WRITE_ATTEMPTED 32768u
#define MAPLESU_EFFECT_CRED_CHANGED 65536u
/* TASK_STATE is a separate current-task transaction. COMPLETED includes a
 * verified already-disabled state; CHANGED records this command's detach.
 * result is the original native seccomp mode. Neither is a cred commit. */
#define MAPLESU_EFFECT_TASK_STATE_CHANGED 131072u
#define MAPLESU_EFFECT_TASK_STATE_COMPLETED 262144u
/* Existing installation revoke/retire facts, including a retained service bool.
 * Both vendor source invalidation and helper OPEN may synchronously close the
 * same installation admission; neither may discard these completed effects. */
#define MAPLESU_INSTALLATION_LIFECYCLE_EFFECTS (MAPLESU_EFFECT_COMMITTED | MAPLESU_EFFECT_PENDING | MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_QUARANTINED | MAPLESU_EFFECT_PUBLICATION_RETAINED | MAPLESU_EFFECT_GATE_PINNED | MAPLESU_EFFECT_TEXT_ATTEMPTED | MAPLESU_EFFECT_WRITTEN | MAPLESU_EFFECT_SHARED_RETAINED)
#define MAPLESU_REQUEST_STATUS_NOMEM (-12)
#define MAPLESU_REQUEST_STATUS_DENIED (-1)
#define MAPLESU_REQUEST_STATUS_BUSY (-16)
#define MAPLESU_REQUEST_STATUS_STALE (-116)
#define MAPLESU_REQUEST_STATUS_IO (-5)

#if defined(_MSC_VER)
#define MAPLESU_WIRE_ALIGN8 __declspec(align(8))
#else
#define MAPLESU_WIRE_ALIGN8 __attribute__((aligned(8)))
#endif
#if defined(__cplusplus)
#define MAPLESU_WIRE_ASSERT(expr) static_assert((expr), #expr)
#define MAPLESU_WIRE_ALIGNOF(type) alignof(type)
#else
#define MAPLESU_WIRE_ASSERT(expr) _Static_assert((expr), #expr)
#define MAPLESU_WIRE_ALIGNOF(type) _Alignof(type)
#endif

/* All wire fields are little-endian fixed-width values, including zero-extended user pointers. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_request_v2 {
    uint32_t magic;
    uint16_t version;
    uint16_t length;
    uint32_t command;
    uint32_t reserved;
    uint64_t request_id;
    uint64_t response_user;
    uint64_t arg0;
    uint64_t arg1;
} maplesu_request_v2;

/* READ: arg0 is the kernel source; arg1 points to this user-space descriptor.
 * Success copies length bytes and returns length. A failed user copy may be partial. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_read_v2 {
    uint64_t destination_user;
    uint32_t length;
    uint32_t reserved;
} maplesu_read_v2;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_response_v2 {
    uint32_t completion_magic;
    uint16_t version;
    uint16_t length;
    uint32_t command;
    int32_t status;
    uint64_t request_id;
    uint64_t result;
    uint64_t metadata_address;
    uint32_t metadata_length;
    uint32_t metadata_version;
} maplesu_response_v2;

/* Immutable descriptor: address = descriptor runtime address + signed displacement.
 * A zero layout_length means no layout descriptor is published in this protocol batch. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_metadata_v2 {
    uint32_t magic;
    uint16_t version;
    uint16_t length;
    uint64_t capabilities;
    int64_t kernel_base_displacement;
    int64_t legacy_metadata_displacement;
    int64_t layout_displacement;
    uint32_t layout_length;
    uint32_t layout_version;
} maplesu_metadata_v2;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_host_instance {
    uint64_t low;
    uint64_t high;
} maplesu_host_instance;

/* QUERY requires expected_epoch=0,arg1=0 and arg0=0 (base view), exactly
 * MAPLESU_OWNER_OPTIONAL_CAPABILITIES (R08), MAPLESU_OWNER_UID_QUERY (R09),
 * MAPLESU_OWNER_PROCATTR_QUERY (R10), or MAPLESU_OWNER_TASK_QUERY. Older views
 * never advertise newer bits.
 * ECHO64 uses arg0=value,arg1=user pointer to host_instance.
 * READ uses arg0=source, arg1=user pointer to maplesu_read_owner_v3.
 * Ordinary ECHO/READ require the exact nonzero epoch and host instance.
 * A bootstrap READ with epoch/host=0 is restricted to the complete published
 * 80-byte descriptor at its exact address, not arbitrary kernel memory. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_request_owner_v3 {
    uint32_t magic;
    uint16_t version;
    uint16_t length;
    uint32_t command;
    uint32_t expected_epoch;
    uint64_t request_id;
    uint64_t response_user;
    uint64_t arg0;
    uint64_t arg1;
} maplesu_request_owner_v3;

typedef maplesu_response_v2 maplesu_response_owner_v3;

/* A retained Image path name, never an installation generation, grant, vendor
 * completion or Root/SU/Ready fact. name has exactly 16 ASCII letters/digits,
 * without a terminator. The SDK publishes it only with its authenticated binding. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_deployment_namespace_v3 {
    uint32_t magic;
    uint16_t schema, bytes;
    uint8_t name[MAPLESU_DEPLOYMENT_NAMESPACE_BYTES];
} maplesu_deployment_namespace_v3;

/* Existing protected xattr record, copied as fixed-width values. Disk metadata
 * remains an observation; only an accepted current-host response is authority. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_installation_record_v3 {
    uint32_t magic;
    uint16_t schema, bytes;
    uint32_t active, reserved;
    uint8_t generation[16], attempt[16];
    char boot[40];
    struct { uint32_t flags, reserved; int64_t code; } stages[3];
    uint32_t publication_source, publication_phase;
    int64_t publication_code;
    uint32_t version_published, publication_reserved;
} maplesu_installation_record_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_installation_state_v3 {
    uint32_t magic;
    uint16_t schema, bytes;
    uint32_t flags, reserved;
    maplesu_host_instance host;
    uint8_t name[16];
    uint64_t revision, device, inode;
    maplesu_installation_record_v3 record;
} maplesu_installation_state_v3;

/* Only authenticated SU_RUNTIME_READ fills this portable native-fact snapshot.
 * No pointer, registry/controller address or fabricated readiness flag. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_su_runtime_status_v3 {
    uint32_t schema, bytes;
    maplesu_host_instance host;
    uint8_t installation_generation[16], runtime_generation[16];
    uint64_t installation_fence;
    uint32_t flags, users, producers, hook_count;
    int32_t status, target_error, cleanup_error, start_error;
    uint32_t target_qualified, installation_flags;
} maplesu_su_runtime_status_v3;

MAPLESU_WIRE_ASSERT(sizeof(maplesu_installation_record_v3) == 160);
MAPLESU_WIRE_ASSERT(offsetof(maplesu_installation_record_v3, stages) == 88);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_installation_state_v3) == 232);
MAPLESU_WIRE_ASSERT(offsetof(maplesu_installation_state_v3, record) == 72);

/* All management commands: arg0=0, arg1 points to this 64-byte descriptor.
 * reserved is zero. WRITE accepts WRITE_TEXT in flags, with handle=0,
 * a four-byte-aligned address and an advertised TEXT_WRITE capability. A byte
 * length may end within a word; the producer preserves its remaining bytes
 * under the native text lock and caps the confirmed result at that length;
 * it uses the target's synchronized text primitive, never generated EXEC.
 * ALLOC accepts ALLOC_CODE with an optional four-byte-aligned near anchor in
 * address; its length is requested bytes. Ordinary ALLOC has address/flags=0.
 * Both have handle/data=0. Other flags are zero. OPEN payload fields are zero.
 * WRITE data_user/length copy to owned handle+address(offset), or handle=0
 * selects an authenticated ordinary kernel address. EXEC consumes handle,
 * address/data=0, length=complete standard-frame bytes. RELEASE handle only.
 * ROOT and TASK_STATE have no payload; CAPS data_user points to the exact
 * 40-byte caps below. TASK_STATE result is the original native mode 0/1/2;
 * its completion/change effects never replace the ROOT credential receipt.
 * CLOSE uses session only (0 closes only the current task execution instance,
 * including recovery from a lost OPEN reply). IDs never authorize another task.
 * For these commands response.result is the full u64 result (OPEN session,
 * ALLOC address, EXEC result). PREPARE has data_user/length=prepare64 and other
 * payload fields zero; result=publication ID, metadata_address=control address.
 * PUBLISH/DISABLE/RETIRE use handle=publication ID, other payload fields zero;
 * metadata_address echoes that ID, result is this call's text confirmation
 * prefix (0 or 4). ALLOC metadata_address is its opaque resource handle.
 * SHARED_BOOL_CREATE has handle/address=0, data_user/length=one byte (0 or 1);
 * success result=address, metadata_address=bool ID, EFFECT_ALLOCATED.
 * SHARED_BOOL_STORE uses handle=bool ID, address=exact returned address and
 * one byte (0 or 1); success result=1, metadata_address=ID, EFFECT_WRITTEN.
 * SHARED_BOOL_RELEASE uses the same ID/address, data/length=0; success result=0,
 * metadata_address=ID, REFERENCE_DROPPED plus exactly RELEASED or SHARED_RETAINED.
 * It drops only the creator reference; each publication has an independent ref.
 * metadata_length is EFFECT bits, metadata_version=0. Errors retain EFFECT
 * bits and meaningful result (partial WRITE count or EXEC u64) on errors;
 * they are never relabelled as success. A lost
 * completion is uncertain, never permission to repeat a mutation. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_management_v3 {
    maplesu_host_instance host;
    uint64_t session;
    uint64_t handle;
    uint64_t address;
    uint64_t data_user;
    uint32_t length;
    uint32_t flags;
    uint64_t reserved;
} maplesu_management_v3;

/* Explicit helper OPEN is the only SESSION_OPEN with expected_epoch=0:
 * management host/session/handle/address/flags/reserved=0, data_user/length is
 * a destination for metadata80. The native current executable, held active
 * installation lease and registered EARLY_HELPER service authenticate it;
 * nothing in this user shape grants authority. Manager OPEN is unchanged.
 * Success result=session, metadata_address=immutable helper descriptor anchor,
 * metadata_length/version=0; metadata80 carries HELPER state, native host,
 * nonzero installation epoch and actual principal fullUID. Its displacement
 * fields refer to that anchor, not to the destination user's address.
 * The helper descriptor advertises only HELPER_BASE plus actual optional bits;
 * READ is limited to the complete descriptor at its exact native address.
 * Existing-session ECHO verifies continuity without QUERY/Manager claim.
 * Only ROOT/TASK_STATE/CRED_PROCATTR/DEPLOYMENT_READ/CLOSE are helper management
 * commands. No CAPS/ALLOC/WRITE/EXEC/publication/UID/installation mutation.
 * A negative completed OPEN retains its native error, SESSION_CLOSED and any
 * actual installation lifecycle effects if native source revalidation closed
 * admission. No session result is required for that completed invalidation.
 * A lost/invalid completion is uncertain: explicit CLOSE(session0)
 * may retire only this task; OPEN/ROOT are never automatically replayed.
 * Fork has no transferred session, exec/exit retire the original task lease,
 * and installation revoke detaches sessions before their in-flight refs drain.
 * Manager owner invalidation does not revoke an independent helper lease. */

/* SU_RUNTIME_READ uses the current authenticated session/host/epoch and exact
 * status104 destination, with handle/address/flags/reserved=0. Manager and
 * genuine helper sessions may read it; no QUERY/capability view is changed.
 * The actual factory getter does not start/claim/refresh/publish or read disk.
 * A completed native error still copies diagnostic facts and returns result104,
 * the same negative status and only possible SESSION_CLOSED. result0 means no
 * complete diagnostic copy. metadata_address/version are zero. Post-authority
 * error never grants permission to publish success. The SDK may expose a
 * validated negative diagnostic with an error return; missing/invalid completion
 * or binding loss preserves its previous output. status0 is factory facts only,
 * not persistent UID authorization, actual application Root or device SU/Ready. */

/* DEPLOYMENT_READ uses the same management64: a nonzero current-task session,
 * data_user/length=destination/exact deployment24, handle/address/flags/reserved=0.
 * Success result=24, metadata_address/length/version=0. A negative completed
 * response has result=0 or24 and only optional SESSION_CLOSED effects. result24
 * records a completed destination copy before a later authority failure, never
 * permission to publish success. No pointer/Key or installation/vendor authority
 * is returned; unsupported/config1 does not retry on another protocol. */

/* INSTALLATION_STATE uses management64 with a current Manager session/host/
 * epoch, handle/flags/reserved=0, address=READ/COMMIT/REVOKE and exact state232.
 * READ publishes the current snapshot, result=232 and effects=0. Mutation input
 * carries the expected host/name/revision/flags; COMMIT also proves the protected
 * directory device/inode and record, REVOKE the current generation. Successful
 * mutation returns result=0 and optional COMMITTED. A negative complete reply
 * keeps the native errno, COMMITTED and/or PENDING plus lifecycle drain effects;
 * the old snapshot is not automatically retried. Explicit READ obtains the new
 * revision/flags before any caller-directed continuation. SESSION_CLOSED can
 * accompany a later authority failure; completed effects never grant authority.
 *
 * VENDOR_STATE uses the same frame/management64, expected_epoch/session=0. Its
 * authentication is the real init-user-ns UID/EUID0 task and the exact protected
 * executable inode held by the host, never a Manager token or a Legacy Key.
 * address=READ/BEGIN/BEFORE/COMPLETE; handle=0 for READ/BEGIN, stage index 0..2
 * otherwise; flags/reserved=0, data_user/length=state232. READ can enroll this
 * exact helper and returns result=232 with effects=0 or COMMITTED. Mutations bind
 * the supplied host and expected snapshot, return the next nonzero revision and
 * COMMITTED. Negative status is -4095..-1, result/metadata_address/version=0;
 * effects are 0, COMMITTED, or COMMITTED|PENDING with actual installation
 * lifecycle drain facts from the same invalidation. Path invalidation monotonically
 * closes active admission and keeps held refs for explicit Manager revoke/drain.
 * PENDING is not a new helper qualification. READ is required at process/thread
 * start, after fork, and after any lost/invalid completion or negative effectful
 * reply; no mutation is replayed or retried across authentication dialects.
 * Complete error/effect receipts survive a later identity failure. Failed calls
 * preserve SDK outputs; neither disk observations nor a copied state grant SU,
 * Root or Ready. Boot helper stage order and the third stage's advisory result
 * remain the existing product semantics. */

/* CRED_PROCATTR uses this same management64: handle/flags/reserved=0,
 * address=CURRENT or EXEC, data_user/length=1..MAX native user bytes. The
 * descriptor is a kernel snapshot; context bytes are copied by the actual
 * proc writer, not by a second pretend kernel-input snapshot. The SDK holds a
 * private stable NUL-terminated array. Only the current task's fixed default
 * procattr endpoint is selected; no arbitrary path, task or attribute name.
 * result is the nonnegative native VFS count, even on a later error. Success
 * requires that count equal length and a valid strict session after native
 * credential replacement. metadata_address/version=0; effects are attempted,
 * observed cred change, and possible SESSION_CLOSED, independent of errno.
 * Native failure/short write never replays via a file write or legacy Key.
 * EXEC updates an attribute; it does not execute a program or preserve a
 * management session through a future real exec. Zero-length clear remains
 * in the legacy boundary until its target contract is qualified. */

/* Same management64 envelope. All reserved/flags fields are zero.
 * CREATE has no payload, returns result=initial revision (1), handle=controller,
 * ALLOCATED|COMMITTED. PREPARE: handle=controller, data=prepare32;
 * result=candidate, handle=controller, ALLOCATED.
 * COMMIT/CANCEL: handle=candidate,address=controller; COMMIT result=revision,
 * handle=controller, COMMITTED|REFERENCE_DROPPED. A bound trusted reader fence
 * closes admission for actual removed/denied/install/Profile deltas first.
 * Negative status with result=0,handle=controller,PENDING[|QUARANTINED] retains
 * the staged candidate and old snapshot. COMMITTED|REFERENCE_DROPPED with a
 * nonzero revision consumes the candidate even if a later error adds PENDING
 * [|QUARANTINED] for unfinished fence cleanup. QUARANTINED requires PENDING.
 * Only an explicit operation in the still-valid original session may continue;
 * CLOSED cleanup can discard candidates. No unknown mutation is replayed.
 * CANCEL result=0, handle=candidate, REFERENCE_DROPPED.
 * READBACK: with a controller handle, data=readback24; destination receives
 * metadata40 followed by count records, result=held revision, handle=controller,
 * effects=0. The fixed runtime form uses handle=0,address=0 and the same
 * descriptor/output layout, projected from one HOST-held immutable snapshot.
 * A failed acquire or row validation may return status=-EINPROGRESS,
 * result=0, metadata_address=0, effects=PENDING; no destination bytes or
 * revision are published on that failure. Other negative receipts have
 * effects=0 unless SESSION_CLOSED is appended.
 * SHUTDOWN: handle=controller only; PENDING without RELEASED preserves the
 * published snapshot while old leases drain. RELEASED|PENDING[|QUARANTINED]
 * retains the real unpublish fact after a fence finish error, with result=0.
 * RELEASED means unpublished, not reader drain without such a bound fence
 * or controller memory freed. Staged candidates belong to their creating session;
 * committed snapshots/controllers outlive Manager epochs and CLOSE. Lost mutation
 * completion is uncertain; recover with explicit readback, never replay COMMIT.
 * Installation/Profile numbers are untrusted scalars until real providers bind
 * them. These commands do not grant SU or create a runtime service identity. */
/* The same authenticated UID_COMMIT/UID_READBACK envelope has fixed boot-runtime
 * shapes. UID_COMMIT handle=0,address=fullUID (excluding UINT32_MAX);
 * fullUID=0 synchronizes the complete already committed authority,
 * data_user/length=0 synchronizes the already committed durable authority; no
 * caller candidate/controller or grant fields are supplied. Its receipt keeps
 * the actual producer stage, error, effects and candidate-consumption facts.
 * UID_READBACK handle=0,address=0 projects the same descriptor/output shape.
 * Acquisition or row validation observing an installation/fence transition
 * returns a negative PENDING receipt and publishes no bytes/revision. Existing
 * nonzero-handle forms, Key/v2, command numbers, layouts and query views stay
 * unchanged; EARLY_HELPER and UID_SU qualifications do not grant this route. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_uid_record_v3 {
    uint32_t full_uid, decision;
    uint64_t installation_generation, profile_revision;
    char package[256];
} maplesu_uid_record_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_uid_metadata_v3 {
    uint32_t schema, count, record_bytes, reserved;
    uint64_t content_revision;
    maplesu_host_instance host;
} maplesu_uid_metadata_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_uid_prepare_v3 {
    uint64_t records_user, expected_revision;
    uint32_t count, record_bytes, schema, reserved;
} maplesu_uid_prepare_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_uid_readback_v3 {
    uint64_t destination_user;
    uint32_t capacity, reserved;
    uint64_t expected_revision;
} maplesu_uid_readback_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_publication_member_v3 {
    uint64_t handle;
    uint32_t role;
    uint32_t reserved;
    uint64_t address; /* Exact shared bool address; ordinary members require 0. */
} maplesu_publication_member_v3;

/* PREPARE records ordinary owned allocations and explicit SHARED_BOOL refs
 * (the role selects a separate bool-ID namespace) before entry exposure.
 * PUBLISH freezes ordinary members and transfers exposed ownership to this boot;
 * shared bool pages stay RW/NX with typed-only updates. Cancelling a publication
 * drops its refs, never the independent creator ref. No caller flag
 * certifies quiescence or permission to free executable code. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_publication_prepare_v3 {
    uint64_t members_user;
    uint32_t member_count;
    uint32_t gate_index;
    uint64_t entry_address;
    uint64_t gate_entry_offset;
    uint32_t expected_instruction;
    uint32_t flags;
    uint64_t payload_entry;
    uint64_t reserved[2];
} maplesu_publication_prepare_v3;

/* Read-only boot-owned control. Trusted generated gate calls enter(id), then
 * returns to a permanent gate exit before leave(id). A zero enter result
 * selects a permanent fallback with no reference to retired payload. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_publication_control_v3 {
    uint64_t publication_id;
    uint64_t enter_function;
    uint64_t leave_function;
    uint64_t payload_entry;
    uint64_t entry_address;
    uint64_t resume_address;
    uint32_t schema;
    uint32_t length;
    uint32_t epoch;
    uint32_t reserved;
} maplesu_publication_control_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_caps_v3 {
    uint64_t inheritable, permitted, effective, bounding, ambient;
} maplesu_caps_v3;

typedef struct MAPLESU_WIRE_ALIGN8 maplesu_read_owner_v3 {
    uint64_t destination_user;
    uint32_t length;
    uint32_t reserved;
    maplesu_host_instance host;
} maplesu_read_owner_v3;

/* common retains the v2 field OFFSETS, not v2 version/length values: its
 * version is 3 and length is 80. Only the gateway can synthesize this snapshot
 * from resident state; ordinary READ must special-case this whole descriptor.
 * Capture allowed/current UID/epoch/host once and revalidate before user copy
 * and completion. An immutable RO append block is not an owner authority. */
typedef struct MAPLESU_WIRE_ALIGN8 maplesu_metadata_owner_v3 {
    maplesu_metadata_v2 common;
    uint32_t owner_schema;
    uint32_t owner_state;
    uint32_t owner_epoch;
    uint32_t owner_full_uid;
    maplesu_host_instance host;
} maplesu_metadata_owner_v3;

#define MAPLESU_WIRE_OFFSET(type, field, offset) MAPLESU_WIRE_ASSERT(offsetof(type, field) == (offset))
MAPLESU_WIRE_ASSERT(sizeof(maplesu_request_v2) == MAPLESU_REQUEST_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_response_v2) == MAPLESU_RESPONSE_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_metadata_v2) == MAPLESU_METADATA_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_read_v2) == 16);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_read_v2) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_request_v2) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_response_v2) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_metadata_v2) == 8);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, magic, 0);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, version, 4);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, length, 6);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, command, 8);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, reserved, 12);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, request_id, 16);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, response_user, 24);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, arg0, 32);
MAPLESU_WIRE_OFFSET(maplesu_request_v2, arg1, 40);
MAPLESU_WIRE_OFFSET(maplesu_read_v2, destination_user, 0);
MAPLESU_WIRE_OFFSET(maplesu_read_v2, length, 8);
MAPLESU_WIRE_OFFSET(maplesu_read_v2, reserved, 12);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, completion_magic, 0);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, version, 4);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, length, 6);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, command, 8);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, status, 12);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, request_id, 16);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, result, 24);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, metadata_address, 32);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, metadata_length, 40);
MAPLESU_WIRE_OFFSET(maplesu_response_v2, metadata_version, 44);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, magic, 0);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, version, 4);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, length, 6);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, capabilities, 8);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, kernel_base_displacement, 16);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, legacy_metadata_displacement, 24);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, layout_displacement, 32);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, layout_length, 40);
MAPLESU_WIRE_OFFSET(maplesu_metadata_v2, layout_version, 44);
MAPLESU_WIRE_ASSERT(sizeof(MAPLESU_OWNER_ENVELOPE_PREFIX) - 1 == MAPLESU_OWNER_ENVELOPE_PREFIX_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_host_instance) == 16);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_management_v3) == 64);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_uid_record_v3) == 280);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_uid_metadata_v3) == 40);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_uid_prepare_v3) == 32);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_uid_readback_v3) == 24);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_uid_record_v3) == 8);
MAPLESU_WIRE_OFFSET(maplesu_uid_record_v3, full_uid, 0);
MAPLESU_WIRE_OFFSET(maplesu_uid_record_v3, decision, 4);
MAPLESU_WIRE_OFFSET(maplesu_uid_record_v3, installation_generation, 8);
MAPLESU_WIRE_OFFSET(maplesu_uid_record_v3, profile_revision, 16);
MAPLESU_WIRE_OFFSET(maplesu_uid_record_v3, package, 24);
MAPLESU_WIRE_OFFSET(maplesu_uid_metadata_v3, content_revision, 16);
MAPLESU_WIRE_OFFSET(maplesu_uid_metadata_v3, host, 24);
MAPLESU_WIRE_OFFSET(maplesu_uid_prepare_v3, expected_revision, 8);
MAPLESU_WIRE_OFFSET(maplesu_uid_prepare_v3, count, 16);
MAPLESU_WIRE_OFFSET(maplesu_uid_prepare_v3, schema, 24);
MAPLESU_WIRE_OFFSET(maplesu_uid_readback_v3, capacity, 8);
MAPLESU_WIRE_OFFSET(maplesu_uid_readback_v3, expected_revision, 16);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_publication_member_v3) == 24);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_publication_prepare_v3) == 64);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_publication_control_v3) == 64);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_publication_prepare_v3) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_publication_control_v3) == 8);
MAPLESU_WIRE_OFFSET(maplesu_publication_member_v3, handle, 0);
MAPLESU_WIRE_OFFSET(maplesu_publication_member_v3, role, 8);
MAPLESU_WIRE_OFFSET(maplesu_publication_member_v3, reserved, 12);
MAPLESU_WIRE_OFFSET(maplesu_publication_member_v3, address, 16);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, members_user, 0);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, member_count, 8);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, gate_index, 12);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, entry_address, 16);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, gate_entry_offset, 24);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, expected_instruction, 32);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, flags, 36);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, payload_entry, 40);
MAPLESU_WIRE_OFFSET(maplesu_publication_prepare_v3, reserved, 48);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, publication_id, 0);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, enter_function, 8);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, leave_function, 16);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, payload_entry, 24);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, entry_address, 32);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, resume_address, 40);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, schema, 48);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, length, 52);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, epoch, 56);
MAPLESU_WIRE_OFFSET(maplesu_publication_control_v3, reserved, 60);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_deployment_namespace_v3) == MAPLESU_DEPLOYMENT_NAMESPACE_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_deployment_namespace_v3) == 8);
MAPLESU_WIRE_ASSERT(offsetof(maplesu_deployment_namespace_v3, magic) == 0);
MAPLESU_WIRE_ASSERT(offsetof(maplesu_deployment_namespace_v3, schema) == 4);
MAPLESU_WIRE_ASSERT(offsetof(maplesu_deployment_namespace_v3, bytes) == 6);
MAPLESU_WIRE_ASSERT(offsetof(maplesu_deployment_namespace_v3, name) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_installation_record_v3) == 8);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, magic, 0);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, schema, 4);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, bytes, 6);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, active, 8);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, reserved, 12);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, generation, 16);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, attempt, 32);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, boot, 48);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, stages, 88);
MAPLESU_WIRE_ASSERT(sizeof(((maplesu_installation_record_v3 *)0)->stages[0]) == 16);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, publication_source, 136);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, publication_phase, 140);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, publication_code, 144);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, version_published, 152);
MAPLESU_WIRE_OFFSET(maplesu_installation_record_v3, publication_reserved, 156);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_installation_state_v3) == 8);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, magic, 0);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, schema, 4);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, bytes, 6);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, flags, 8);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, reserved, 12);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, host, 16);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, name, 32);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, revision, 48);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, device, 56);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, inode, 64);
MAPLESU_WIRE_OFFSET(maplesu_installation_state_v3, record, 72);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_su_runtime_status_v3) == 104);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_su_runtime_status_v3) == 8);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, schema, 0);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, bytes, 4);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, host, 8);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, installation_generation, 24);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, runtime_generation, 40);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, installation_fence, 56);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, flags, 64);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, users, 68);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, producers, 72);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, hook_count, 76);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, status, 80);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, target_error, 84);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, cleanup_error, 88);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, start_error, 92);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, target_qualified, 96);
MAPLESU_WIRE_OFFSET(maplesu_su_runtime_status_v3, installation_flags, 100);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_caps_v3) == 40);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, session, 16);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, handle, 24);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, address, 32);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, data_user, 40);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, length, 48);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, flags, 52);
MAPLESU_WIRE_OFFSET(maplesu_management_v3, reserved, 56);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_request_owner_v3) == MAPLESU_REQUEST_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_response_owner_v3) == MAPLESU_RESPONSE_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_read_owner_v3) == MAPLESU_OWNER_READ_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(sizeof(maplesu_metadata_owner_v3) == MAPLESU_OWNER_METADATA_WIRE_BYTES);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_request_owner_v3) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_read_owner_v3) == 8);
MAPLESU_WIRE_ASSERT(MAPLESU_WIRE_ALIGNOF(maplesu_metadata_owner_v3) == 8);
MAPLESU_WIRE_OFFSET(maplesu_request_owner_v3, expected_epoch, 12);
MAPLESU_WIRE_OFFSET(maplesu_request_owner_v3, request_id, 16);
MAPLESU_WIRE_OFFSET(maplesu_request_owner_v3, response_user, 24);
MAPLESU_WIRE_OFFSET(maplesu_request_owner_v3, arg0, 32);
MAPLESU_WIRE_OFFSET(maplesu_request_owner_v3, arg1, 40);
MAPLESU_WIRE_OFFSET(maplesu_read_owner_v3, host, 16);
MAPLESU_WIRE_OFFSET(maplesu_metadata_owner_v3, owner_schema, 48);
MAPLESU_WIRE_OFFSET(maplesu_metadata_owner_v3, owner_state, 52);
MAPLESU_WIRE_OFFSET(maplesu_metadata_owner_v3, owner_epoch, 56);
MAPLESU_WIRE_OFFSET(maplesu_metadata_owner_v3, owner_full_uid, 60);
MAPLESU_WIRE_OFFSET(maplesu_metadata_owner_v3, host, 64);

#undef MAPLESU_WIRE_OFFSET
#undef MAPLESU_WIRE_ASSERT
#undef MAPLESU_WIRE_ALIGNOF
#undef MAPLESU_WIRE_ALIGN8
#endif
