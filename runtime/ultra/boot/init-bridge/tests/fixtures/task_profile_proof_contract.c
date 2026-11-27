/* Host port with unmodified production binding and publication function bodies. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
typedef uint32_t u32;
typedef uint64_t u64;
typedef int64_t s64;
#define CONFIG_GENERIC_ENTRY 0
#define CONFIG_ARM64 1
#define CONFIG_64BIT 1
#define CONFIG_THREAD_INFO_IN_TASK 1
#define CONFIG_HAVE_SYSCALL_TRACEPOINTS 1
#define CONFIG_KRETPROBES 1
#define MSU_INIT_BRIDGE_HOST_IMPORTS 1
#define MSU_INIT_BRIDGE_RUNTIME_FACTS 1
#define MSU_INIT_BRIDGE_CFI_LEGACY 0
#define MSU_TASK_PROFILE_NATIVE 1
#define IS_ENABLED(x) (x)
#define READ_ONCE(x) (x)
#define smp_load_acquire(p) (*(p))
#define noinline
#define __used
#define __visible
#include "binding.inc"
struct msu_task_profile_target_binding msu_task_profile_target;
struct msu_task_profile_marker_binding msu_task_profile_marker_target;
struct list_head { struct list_head *next, *prev; };
struct msu_runtime_layout_field { u32 offset, bytes, owner_bytes; };
enum { MSU_DYN_thread_info_flags, MSU_DYN_thread_info_syscall_work, MSU_DYN_task_struct_thread_info,
       MSU_DYN_task_struct_signal, MSU_DYN_task_struct_thread_node, MSU_DYN_signal_struct_thread_head, FIELD_COUNT };
static struct { struct msu_runtime_layout_field values[FIELD_COUNT]; } msu_runtime_layout;
struct msu_runtime_btf_field { u32 offset, bytes, owner_bytes, type_id, kind; };
static bool layout_ready = true, msu_task_profile_live_published;
static int publication, stage_error, stage, failed_stage;
static void publish(void *pointer, u32 value, size_t bytes)
{
    if (pointer == &msu_task_profile_marker_target.bound) {
        assert(publication == 0 && !msu_task_profile_live_published && !msu_task_profile_target.bound);
    } else if (pointer == &msu_task_profile_live_published) {
        assert(publication == 1 && msu_task_profile_marker_target.bound == 1 && !msu_task_profile_target.bound);
    } else {
        assert(pointer == &msu_task_profile_target.bound && publication == 2);
        assert(msu_task_profile_live_published && msu_task_profile_marker_target.bound == 1 && value == 2);
    }
    publication++;
    if (bytes == sizeof(bool)) *(bool *)pointer = value;
    else { assert(bytes == sizeof(u32)); *(u32 *)pointer = value; }
}
#define smp_store_release(p, value) publish((p), (value), sizeof(*(p)))
static bool msu_runtime_layout_ready(void) { return layout_ready; }
static int msu_runtime_facts_field(const char *owner, const char *name, struct msu_runtime_btf_field *field)
{
    *field = (struct msu_runtime_btf_field){.offset=0, .bytes=8, .owner_bytes=16, .kind=1};
    if (!strcmp(owner, "task_struct") && !strcmp(name, "thread_info")) { field->kind=4; field->bytes=16; field->owner_bytes=64; }
    else if (!strcmp(name, "stack")) field->kind=2;
    else if (!strcmp(name, "thread_node") || !strcmp(name, "thread_head")) field->bytes=sizeof(struct list_head);
    return 0;
}
static int msu_runtime_btf_enum(const char *name, s64 *value) { (void)name; *value=63; return 0; }
static int msu_profile_dynamic_field(const char *owner, const char *field, u32 slot)
{ (void)owner; (void)field; (void)slot; return 0; }
static int msu_profile_value(u32 id, u32 kind, u32 bytes, const char *name)
{ (void)id; (void)kind; (void)bytes; (void)name; return 0; }
static int next_stage(void) { return ++stage == failed_stage ? stage_error : 0; }
static int msu_profile_marker(struct msu_task_profile_marker_binding *marker)
{ memset(marker, 0, sizeof(*marker)); return next_stage(); }
static int msu_profile_trace_callback(unsigned long (*lookup)(const char *)) { (void)lookup; return next_stage(); }
static int msu_profile_regs(unsigned long (*lookup)(const char *)) { (void)lookup; return next_stage(); }
static int msu_profile_workbit(unsigned long (*lookup)(const char *), u32 member, u32 *bit)
{ (void)lookup; (void)member; *bit=5; return next_stage(); }
static unsigned long lookup(const char *name) { (void)name; return 0x1000; }
bool msu_task_profile_runtime_ready(void);
#include "consumer.inc"
#include "producer.inc"
static void reset(void)
{
    msu_task_profile_target = (struct msu_task_profile_target_binding){.magic=MSU_TASK_PROFILE_TARGET_MAGIC,
        .schema=MSU_TASK_PROFILE_TARGET_SCHEMA, .bytes=sizeof(msu_task_profile_target)};
    msu_task_profile_marker_target = (struct msu_task_profile_marker_binding){.magic=MSU_TASK_PROFILE_MARKER_MAGIC,
        .schema=1, .bytes=sizeof(msu_task_profile_marker_target)};
    for (unsigned i=0;i<FIELD_COUNT;i++) msu_runtime_layout.values[i]=(struct msu_runtime_layout_field){0,8,16};
    layout_ready=true; msu_task_profile_live_published=false; publication=stage=stage_error=failed_stage=0;
}
static void image(u32 mask)
{
    reset(); msu_task_profile_target.bound=1; msu_task_profile_target.proof_mask=mask;
    msu_task_profile_target.root_bit=63; msu_task_profile_target.work_bit=5;
    msu_task_profile_target.target_sha256[0]=1; msu_task_profile_target.config_sha256[0]=1;
    msu_task_profile_target.proof_sha256[0]=1;
}
int main(int argc, char **argv)
{
    assert(argc==2); const char *name=argv[1]; reset();
    if (!strcmp(name,"abi")) { assert(sizeof(msu_task_profile_target)==136 && MSU_TASK_PROFILE_TARGET_SCHEMA==1); assert(MSU_TASK_PROFILE_TARGET_PROOFS==31 && MSU_TASK_PROFILE_RUNTIME_PROOFS==31); }
    else if (!strcmp(name,"image-five")) { image(31); assert(msu_task_profile_target_ready()); }
    else if (!strcmp(name,"image-legacy-six")) { image(63); assert(msu_task_profile_target_ready()); }
    else if (!strcmp(name,"image-missing")) { for(unsigned bit=0;bit<5;bit++) { image(31 & ~(1U<<bit)); assert(!msu_task_profile_target_ready()); image(63 & ~(1U<<bit)); assert(!msu_task_profile_target_ready()); } }
    else if (!strcmp(name,"image-extra")) { image(127); assert(!msu_task_profile_target_ready()); image(95); assert(!msu_task_profile_target_ready()); }
    else if (!strcmp(name,"image-hashes")) { for(unsigned i=0;i<3;i++) { image(31); unsigned char *hashes[]={msu_task_profile_target.target_sha256,msu_task_profile_target.config_sha256,msu_task_profile_target.proof_sha256}; memset(hashes[i],0,32); assert(!msu_task_profile_target_ready()); } }
    else if (!strcmp(name,"runtime-publication")) { assert(!msu_task_profile_runtime_produce(lookup)); assert(publication==3 && stage==4); assert(msu_task_profile_target.bound==2 && msu_task_profile_target.proof_mask==31); assert(msu_task_profile_runtime_ready() && msu_task_profile_target_ready()); for(unsigned i=0;i<32;i++) assert(!msu_task_profile_target.target_sha256[i] && !msu_task_profile_target.config_sha256[i] && !msu_task_profile_target.proof_sha256[i]); }
    else if (!strcmp(name,"runtime-false-ready")) { image(31); msu_task_profile_target.bound=2; assert(!msu_task_profile_runtime_ready() && !msu_task_profile_target_ready()); assert(msu_task_profile_runtime_produce(lookup)==-ESTALE && !publication); }
    else if (!strcmp(name,"runtime-legacy-mask")) { image(63); msu_task_profile_target.bound=2; msu_task_profile_live_published=true; assert(!msu_task_profile_target_ready()); }
    else if (!strcmp(name,"runtime-failure")) { for(int failed=1;failed<=4;failed++) { reset(); failed_stage=failed; stage_error=-EFAULT; assert(msu_task_profile_runtime_produce(lookup)==-EFAULT); assert(!publication && !msu_task_profile_runtime_ready() && !msu_task_profile_target.bound && !msu_task_profile_marker_target.bound && !msu_task_profile_target_ready()); } }
    else if (!strcmp(name,"unbound")) { assert(!msu_task_profile_target_ready()); image(31); msu_task_profile_target.bound=3; assert(!msu_task_profile_target_ready()); }
    else if (!strcmp(name,"layout")) { image(31); msu_runtime_layout.values[0].bytes=4; assert(!msu_task_profile_target_ready()); image(31); msu_task_profile_target.work_bit=63; assert(!msu_task_profile_target_ready()); image(31); layout_ready=false; assert(!msu_task_profile_target_ready()); }
    else assert(0);
    puts("PASS"); return 0;
}
