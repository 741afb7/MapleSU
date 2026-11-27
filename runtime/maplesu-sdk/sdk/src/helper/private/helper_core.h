// Shared state and small utilities of the first-party boot helper.
//
// The helper is deliberately freestanding: the transaction sources include no
// libc headers and use this module's bounded string/path helpers instead, so
// the same code can be built for AArch64/Android and for the wasm32 host test.
#ifndef MAPLESU_HELPER_CORE_H
#define MAPLESU_HELPER_CORE_H

#include "helper_ops.h"

// Version printed on the boot log ("MapleSU: <major>.<minor>.<patch>").
#define HELPER_VERSION_MAJOR 5
#define HELPER_VERSION_MINOR 2
#define HELPER_VERSION_PATCH 1

// Boot-session actions produced by helper_session_begin().
enum helper_session_action {
    HELPER_SESSION_NORMAL = 0,     // fresh session, safe flag is '1'
    HELPER_SESSION_PROTECTED = 1,  // previous session failed and protection stays
    HELPER_SESSION_RETRY = 2,      // previous session failed, protection disabled
    HELPER_SESSION_COMPLETED = 3,  // this boot already initialized the SDK
};

// Exit codes of one boot run.
enum {
    HELPER_EXIT_OK = 0,
    HELPER_EXIT_USAGE = 2,
    HELPER_EXIT_ENVIRONMENT = 3,
    HELPER_EXIT_PROTECTED = 4,
};

// Flag/receipt file suffixes, relative to the installed helper path.
#define HELPER_SUFFIX_SAFE_FLAG ".safe_flag"
#define HELPER_SUFFIX_DUPLICATE ".boot_session.duplicate"
#define HELPER_SUFFIX_COMPLETED ".boot_session.completed"
#define HELPER_SUFFIX_WORK ".boot_session.work"
#define HELPER_SUFFIX_FAIL_PROTECT ".boot_fail_protect"
#define HELPER_SUFFIX_LOCK ".running.lock"

// Directory holding the paired environment-policy backend, opened one level at
// a time from "/data" without following symlinks.
#define HELPER_POLICY_ROOT "/data"
#define HELPER_POLICY_PARTS "adb"
#define HELPER_POLICY_PARTS2 "maplesu"
#define HELPER_POLICY_PARTS3 "bin"

#define HELPER_PACKAGES_FILE "/data/system/packages.list"
#define HELPER_SELINUX_LABEL "u:object_r:system_file:s0"
#define HELPER_UID_PRIVATE_DIR "u-00000000"

// Module identifier this helper reports to the SDK (exactly 32 alphanumeric
// characters, the shape maplesu_helper_su_write validates).
#define HELPER_MODULE_ID "maplesuenvironmentbootstrapv1001"
static_assert(sizeof(HELPER_MODULE_ID) == HELPER_MODULE_LEN + 1, "helper module ID must retain the 32-byte ABI");

struct helper_context {
    struct helper_ops ops; /* copied table, so callers keep ownership of theirs */
    char self_path[HELPER_PATH_MAX]; /* installed helper: <data dir>/init */
    char env_dir[HELPER_PATH_MAX];   /* <data dir> without trailing slash */
    char module_id[HELPER_MODULE_LEN + 1];
    char boot_id[HELPER_BOOT_ID_LEN + 1];
    int lock_fd;
    int policy_ready;
    int policy_error;
    char capability_stage;
    helper_i64 capability_error;
    int capability_record_error;
};

// Bounded text helpers (no libc).
helper_u32 helper_strlen(const char* text);
int helper_streq(const char* left, const char* right);
int helper_memeq(const void* left, const void* right, helper_u32 length);
void helper_memzero(void* buffer, helper_u32 length);
void helper_memcopy(void* destination, const void* source, helper_u32 length);
int helper_append(char* out, helper_u32 capacity, helper_u32* length, const char* text);
int helper_append_char(char* out, helper_u32 capacity, helper_u32* length, char value);
int helper_append_uint(char* out, helper_u32 capacity, helper_u32* length, helper_u32 value);
int helper_format_i64(char* out, helper_u32 capacity, helper_i64 value);
int helper_is_lower_hex(char value);
int helper_is_upper_hex(char value);
int helper_is_hex(char value);
int helper_is_alpha_numeric(char value);
int helper_boot_id_valid(const char* text, helper_u32 length);
int helper_package_name_valid(const char* name, helper_u32 length);

// Operational logging (one line per call, no format strings).
void helper_log(struct helper_context* context, const char* text);
void helper_log_pair(struct helper_context* context, const char* prefix, const char* value);
void helper_log_error(struct helper_context* context, const char* prefix, helper_i64 error);

// Paths relative to the installed helper.
int helper_state_path(const struct helper_context* context, const char* suffix,
                      char* out, helper_u32 capacity);
// The transaction sources do not allocate; these are the scratch limits they
// accept for one package list and one directory page.
#define HELPER_MAX_PACKAGES 256
#define HELPER_MAX_DIR_ENTRIES 256

// Boot orchestration (helper_boot.cpp).
int helper_run_boot(const struct helper_ops* ops, int argc, char* const argv[],
                    char* const envp[] = nullptr);

// Boot session state machine (helper_session.cpp).
int helper_session_begin(struct helper_context* context, int* action);
int helper_session_finish(struct helper_context* context);
int helper_session_fail(struct helper_context* context, helper_i64 error);
// Atomically replace a private receipt only after its bytes are synced.
int helper_session_publish(struct helper_context* context, const char* suffix,
                           const void* data, helper_u32 length);

// Environment policy backend (helper_policy.cpp).
int helper_policy_start(struct helper_context* context, char* message,
                        helper_u32 capacity);

// Error codes shared by the two primitives.
enum {
    HELPER_SU_E_PARAM = -20000,
    HELPER_SU_E_ENVIRONMENT = -21010,
    HELPER_SU_E_CANONICAL = -21011,
    HELPER_SU_E_PACKAGES = -21012,
    HELPER_SU_E_COPY = -21013,
    HELPER_SU_E_OPEN = -21014,
    HELPER_SU_E_EXEC = -21015,
};

#endif /* MAPLESU_HELPER_CORE_H */
