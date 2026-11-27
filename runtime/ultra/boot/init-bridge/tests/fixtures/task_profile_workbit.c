#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef uint32_t u32;
typedef uint8_t u8;
typedef int32_t s32;
typedef int64_t s64;
#define GFP_KERNEL 0
#define kmalloc(n, f) malloc(n)
#define kmalloc_array(n, s, f) calloc(n, s)
#define kvcalloc(n, s, f) calloc(n, s)
#define kfree(p) free(p)
#define kvfree(p) free(p)
#include "workbit.inc"

struct captured {
    unsigned long address, helper_address, tracepoint;
    u32 *code, *helper;
    u32 words, helper_words, funcs, work_offset;
    s32 *jumps, *helper_jumps;
};
#include "captured.inc"
static struct captured active;
static unsigned helper_reads;
static struct { s32 code, target; u32 key[2]; } jump_table[4096];
static unsigned jump_count;
#define JUMP_BASE 0x40000000UL
enum { MSU_DYN_task_struct_thread_info, MSU_DYN_thread_info_syscall_work, MSU_DYN_thread_info_flags };
static struct { struct { u32 offset; } values[3]; } msu_runtime_layout;
struct msu_runtime_btf_field { u32 bytes, offset; };
struct msu_runtime_btf_type { u32 kind, bytes; };
struct msu_runtime_btf_function { u32 argument_count, arguments[8]; };
static unsigned long lookup(const char *name)
{
    if (!strcmp(name,"syscall_trace_enter")) return active.address;
    if (!strcmp(name,"trace_sys_enter")) return active.helper_address;
    if (!strcmp(name,"__tracepoint_sys_enter")) return active.tracepoint;
    if (!strcmp(name,"__start___jump_table")) return JUMP_BASE;
    if (!strcmp(name,"__stop___jump_table")) return JUMP_BASE + jump_count * 16;
    return 0;
}
static int msu_init_bridge_lookup_size(const char *name, unsigned long *address, unsigned long *bytes)
{
    *address=lookup(name);
    if (!*address) return -ENOENT;
    if (!strcmp(name,"trace_sys_enter")) { ++helper_reads; *bytes=active.helper_words*4; }
    else *bytes=active.words*4;
    return 0;
}
static int msu_runtime_facts_field(const char *owner, const char *member, struct msu_runtime_btf_field *field)
{
    (void)owner; field->bytes=4;
    if (!strcmp(member,"funcs")) { field->bytes=8; field->offset=active.funcs; }
    else field->offset=!strcmp(member,"target")?4:0;
    return 0;
}
static int msu_runtime_facts_type_size(const char *name, u32 *bytes) { (void)name; *bytes=16; return 0; }
static int msu_runtime_btf_function(const char *n, struct msu_runtime_btf_function *f) { (void)n;(void)f;return -ENOENT; }
static int msu_runtime_btf_type(u32 id, struct msu_runtime_btf_type *t) { (void)id;(void)t;return -ENOENT; }
static int msu_runtime_btf_enum(const char *n, s64 *v) { (void)n;(void)v;return -ENOENT; }
static int copy_from_kernel_nofault(void *to, const void *from, unsigned long bytes)
{
    unsigned long at=(uintptr_t)from, base[]={active.address,active.helper_address,JUMP_BASE};
    unsigned long sizes[]={active.words*4,active.helper_words*4,jump_count*16};
    const void *data[]={active.code,active.helper,jump_table};
    for(unsigned i=0;i<3;i++) if(at>=base[i] && at-base[i]<=sizes[i] && bytes<=sizes[i]-(at-base[i])) {
        memcpy(to,(const u8*)data[i]+(at-base[i]),bytes);return 0;
    }
    return -EFAULT;
}
#include "producer.inc"

static u32 branch(unsigned long pc, unsigned long target, bool link)
{ return (link?0x94000000U:0x14000000U) | (((target-pc)/4)&0x3ffffffU); }
static u32 page(unsigned long pc, unsigned long target, u32 rd)
{
    s64 pages=((s64)(target&~4095UL)-(s64)(pc&~4095UL))/4096;
    return 0x90000000U | ((pages&3)<<29) | (((pages>>2)&0x7ffff)<<5) | rd;
}
static void select_capture(struct captured input)
{
    active=input; helper_reads=jump_count=0;
    msu_runtime_layout.values[MSU_DYN_thread_info_flags].offset=active.work_offset;
    for(unsigned side=0;side<2;side++) {
        u32 words=side?active.helper_words:active.words;
        unsigned long base=side?active.helper_address:active.address;
        s32 *jumps=side?active.helper_jumps:active.jumps;
        for(u32 i=0;jumps && i<words;i++) if(jumps[i]>=0) {
            unsigned long entry=JUMP_BASE+jump_count*16;
            jump_table[jump_count].code=(s32)(base+i*4-entry);
            jump_table[jump_count++].target=(s32)(base+jumps[i]*4-entry-4);
        }
    }
}
static u32 code[24], helper[8];
static s32 jumps[24], helper_jumps[8];
static void synthetic(void)
{
    for(unsigned i=0;i<24;i++) {code[i]=0xd503201fU;jumps[i]=-1;}
    for(unsigned i=0;i<8;i++) {helper[i]=0xd503201fU;helper_jumps[i]=-1;}
    struct captured fixture={.address=0x100000,.helper_address=0x200000,.tracepoint=0x300040,
        .code=code,.helper=helper,.words=24,.helper_words=8,.funcs=64,.jumps=jumps,.helper_jumps=helper_jumps};
    code[0]=0xd5384114U; code[1]=0xf9400288U;
    code[2]=0x36000000U | (13U<<19) | ((20U-2U)<<5) | 8;
    code[3]=page(fixture.address+12,fixture.tracepoint+fixture.funcs,9);
    code[4]=0xb9401288U; code[5]=0x11000508U; code[6]=0xb9001288U;
    code[7]=0xf9400000U | (((fixture.tracepoint+fixture.funcs)&4095)/8<<10) | (9U<<5) | 21;
    code[8]=code[20]=0xd65f03c0U;
    helper[0]=0xd503201fU; helper_jumps[0]=2; helper[1]=0xd65f03c0U;
    helper[2]=page(fixture.helper_address+8,fixture.tracepoint+fixture.funcs,9);
    helper[3]=code[7]; helper[4]=0xd65f03c0U;
    select_capture(fixture);
}
int main(int argc, char **argv)
{
    assert(argc==2);const char *name=argv[1]; u32 bit=99; int expected=0; unsigned reads=0;
    synthetic();
    if(!strcmp(name,"actual-k70u")) {select_capture(k70u);bit=10;}
    else if(!strcmp(name,"actual-618")) {select_capture(v618);bit=10;reads=1;}
    else if(!strcmp(name,"nonadjacent")) bit=13;
    else if(!strcmp(name,"helper-unreachable")) {
        code[21]=branch(active.address+84,active.helper_address,true);code[22]=0xd65f03c0U;
        helper[2]=helper[3]=0xd503201fU;bit=13;
    }
    else if(!strcmp(name,"clobber")) {code[4]=0xd2800009U;expected=-ENOENT;}
    else {
        code[3]=branch(active.address+12,active.helper_address,true); code[4]=0xd65f03c0U;
        bit=13; reads=1;
        if(!strcmp(name,"helper-name-only")) helper[2]=helper[3]=0xd503201fU;
        else if(!strcmp(name,"helper-wrong-object")) helper[3]+=1U<<10;
        else if(!strcmp(name,"helper-both-edges")) {code[20]=branch(active.address+80,active.helper_address,true);code[21]=0xd65f03c0U;}
        else assert(!strcmp(name,"helper"));
        if(strcmp(name,"helper")) expected=-ENOENT;
    }
    u32 output=99;int result=msu_profile_workbit(lookup,0,&output);
    if(result!=expected || (!result && output!=bit) || helper_reads!=reads) {
        fprintf(stderr,"%s result=%d expected=%d bit=%u expectedBit=%u helperReads=%u expectedReads=%u\n",name,result,expected,output,bit,helper_reads,reads);return 1;
    }
    return 0;
}
