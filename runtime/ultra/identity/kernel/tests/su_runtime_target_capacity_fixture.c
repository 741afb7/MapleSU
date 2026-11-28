#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "request_protocol.h"
#undef EOVERFLOW
#define EOVERFLOW 75
#undef EOPNOTSUPP
#define EOPNOTSUPP 95
#undef ESTALE
#define ESTALE 116
_Static_assert(sizeof(unsigned long) == 8, "ARM64 word width");
_Static_assert(sizeof(maplesu_su_runtime_status_v3) == 104, "status wire ABI");
typedef uint32_t u32;
typedef uint64_t u64;
typedef uint32_t compat_uptr_t;
#define __user
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define MSU_SU_RUNTIME_MAX_HOOKS MAPLESU_SU_RUNTIME_MAX_HOOKS
#define MSU_SU_EXEC_FLAGS_REGISTER (2 + 2 * sizeof(struct user_arg_ptr) / sizeof(unsigned long))
#define MSU_INIT_BRIDGE_HOST_IMPORTS 1
#define MSU_INIT_BRIDGE_RUNTIME_FACTS 1
#define MSU_INIT_BRIDGE_CFI_LEGACY 0
#define MODULE 1
#define noinline __attribute__((noinline))
#ifndef __has_feature
#define __has_feature(x) 0
#endif
#define smp_load_acquire(p) (*(p))
#define smp_store_release(p,v) (*(p) = (v))
#define WRITE_ONCE(p,v) ((p) = (v))
#define DEFINE_MUTEX(n) int n
#define mutex_lock(p) ((void)(p))
#define mutex_unlock(p) ((void)(p))
struct filename { const char *name; char rest[24]; };
struct pt_regs { unsigned long regs[31]; };
static int layout_ready = 1, task_context = 1, fail_read, fail_cfi;
static int msu_runtime_layout_ready(void) { return layout_ready; }
static int in_task(void) { return task_context; }
static int in_atomic(void) { return 0; }
static int irqs_disabled(void) { return 0; }
static int msu_su_runtime_profiles_produce(unsigned long (*lookup)(const char *),
    int (*resolve)(const char *, const void *, unsigned long *));
static int msu_su_runtime_prototype(const char *name, bool exec)
{ (void)name; (void)exec; return 0; }
static int msu_task_profile_trace_bind(unsigned long (*lookup)(const char *))
{ (void)lookup; return 0; }
static int copy_from_kernel_nofault(void *to, const void *from, size_t bytes)
{ if (fail_read) return -EFAULT; memcpy(to, from, bytes); return 0; }
#include "target.inc"

struct symbol { const char *name; bool present; u32 code[7]; };
static struct symbol symbols[] = {
    {"do_execveat_common",true,{0}}, {"__arm64_sys_newfstatat",true,{0}},
    {"__arm64_sys_faccessat",true,{0}}, {"__arm64_sys_statx",true,{0}},
    {"__arm64_sys_faccessat2",true,{0}}, {"__arm64_compat_sys_newstat",true,{0}},
    {"__arm64_compat_sys_newlstat",true,{0}}, {"__arm64_sys_stat64",true,{0}},
    {"__arm64_sys_lstat64",true,{0}}, {"__arm64_sys_fstatat64",true,{0}},
    {"__arm64_sys_setresuid",true,{0}}, {"__arm64_sys_setresuid16",true,{0}},
    {"__arm64_sys_reboot",true,{0}}, {"__arm64_sys_ni_syscall",true,{0}},
};
static unsigned long compat_table[440];
static unsigned long lookup(const char *name)
{
    if (!strcmp(name, "compat_sys_call_table")) return (unsigned long)compat_table;
    for (size_t i = 0; i < ARRAY_SIZE(symbols); ++i)
        if (symbols[i].present && !strcmp(name, symbols[i].name))
            return (unsigned long)&symbols[i].code[1];
    return 0;
}
static int resolve(const char *name, const void *reference, unsigned long *entry)
{
    (void)reference;
    if (fail_cfi) return -EOPNOTSUPP;
    *entry = lookup(name);
    return *entry ? 0 : -ENOENT;
}
static int msu_su_runtime_profiles_produce(unsigned long (*native)(const char *),
    int (*resolve_cfi)(const char *, const void *, unsigned long *))
{
    (void)resolve_cfi;
    for (u32 i = 0; i < MSU_SU_TARGET_PROFILE_COUNT; ++i) {
        unsigned long entry = native(msu_su_target_profiles[i].name);
        msu_su_target_profiles[i].kcfi = entry ? *(const u32 *)(entry - 4) : 0;
        if (entry) memcpy(msu_su_target_profiles[i].entry, (const void *)entry, 24);
        else memset(msu_su_target_profiles[i].entry, 0, 24);
    }
    msu_su_runtime_profiles.ready = 1;
    return 0;
}
static void reset(u32 optional, bool uid16, u32 compat)
{
    memset(msu_su_targets, 0, sizeof(msu_su_targets));
    memset(msu_su_runtime_target_facts, 0, sizeof(msu_su_runtime_target_facts));
    msu_su_runtime_profiles.ready = 0;
    layout_ready = task_context = 1;
    fail_read = fail_cfi = 0;
    for (u32 i = 0; i < ARRAY_SIZE(symbols); ++i) {
        symbols[i].present = true;
        for (u32 j = 0; j < 7; ++j) symbols[i].code[j] = 0x12340000 + i * 8 + j;
    }
    symbols[3].present = optional & 1;
    symbols[4].present = optional & 2;
    symbols[11].present = uid16;
    for (u32 i = 0; i < ARRAY_SIZE(compat_table); ++i)
        compat_table[i] = lookup("__arm64_sys_ni_syscall");
#ifdef CONFIG_COMPAT
    for (u32 i = 0; i < ARRAY_SIZE(msu_su_compat_targets); ++i) {
        unsigned long entry = lookup(msu_su_compat_targets[i].name);
        if (entry && (compat & (1u << i))) compat_table[msu_su_compat_targets[i].number] = entry;
    }
#else
    (void)compat;
#endif
}
static u32 expected_count(u32 optional, bool uid16, u32 compat)
{
    u32 count = 3 + !!(optional & 1) + !!(optional & 2) + 2 + uid16;
#ifdef CONFIG_COMPAT
    for (u32 i = 0; i < 5; ++i) count += !!(compat & (1u << i));
#else
    (void)compat;
#endif
    return count;
}
static void unpublished(void)
{
    assert(!msu_su_runtime_target_facts[8] && !msu_su_runtime_target_facts[7]);
    const unsigned char *p = (const void *)msu_su_targets;
    for (size_t i = 0; i < sizeof(msu_su_targets); ++i) assert(!p[i]);
}
int main(void)
{
    u32 passed = 0, failures = 0, maximum = 0;
#ifdef CONFIG_COMPAT
    const u32 masks = 256;
#else
    const u32 masks = 1;
#endif
    for (u32 optional = 0; optional < 4; ++optional)
        for (u32 uid16 = 0; uid16 < 2; ++uid16)
            for (u32 compat = 0; compat < masks; ++compat) {
                reset(optional, uid16, compat);
                const u32 expected = expected_count(optional, uid16, compat);
                int error = msu_su_runtime_target_bind(lookup, resolve);
                if (error || msu_su_runtime_target_facts[7] != expected ||
                    msu_su_runtime_target_facts[8] != 1) {
                    if (!failures) fprintf(stderr, "first failure cap=%u expected=%u error=%d ready=%u\n",
                        MAPLESU_SU_RUNTIME_MAX_HOOKS, expected, error, msu_su_runtime_target_facts[8]);
                    ++failures;
                    if (error) unpublished();
                    continue;
                }
                assert(msu_su_targets[expected - 1].kind == MSU_SU_KSU_REBOOT);
                assert(msu_su_runtime_target_bind(lookup, resolve) == -EALREADY);
                if (expected > maximum) maximum = expected;
                ++passed;
            }
    reset(3, true, 255); layout_ready = 0;
    assert(msu_su_runtime_target_bind(lookup, resolve) == -EPROTO); unpublished();
    reset(3, true, 255); task_context = 0;
    assert(msu_su_runtime_target_bind(lookup, resolve) == -EWOULDBLOCK); unpublished();
    reset(3, true, 255); fail_read = 1;
    assert(msu_su_runtime_target_bind(lookup, resolve) == -EFAULT); unpublished();
    reset(3, true, 255); fail_cfi = 1;
    assert(msu_su_runtime_target_bind(lookup, resolve) == -EOPNOTSUPP); unpublished();
    reset(3, true, 255); symbols[10].present = false;
    assert(msu_su_runtime_target_bind(lookup, resolve) == -ENOENT); unpublished();
#ifdef CONFIG_COMPAT
    reset(3, true, 255); compat_table[106] = lookup("__arm64_sys_stat64");
    assert(msu_su_runtime_target_bind(lookup, resolve) == -ESTALE); unpublished();
#endif
    printf("capacity=%u positives=%u failures=%u maxHooks=%u wireBytes=%zu\n",
        MAPLESU_SU_RUNTIME_MAX_HOOKS, passed, failures, maximum, sizeof(maplesu_su_runtime_status_v3));
    return failures ? 1 : 0;
}