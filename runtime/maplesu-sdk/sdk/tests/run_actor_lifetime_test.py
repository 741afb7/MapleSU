#!/usr/bin/env python3
"""Run production HBT actor staging and emitted iterate_dir callback with I/O seams.

The allocation/session/installer boundaries are fixtures. AsmJit and callback
instructions come from production. Unicorn verifies actor restoration and the
complete original invocation; this is not kernel sleep/drain or hardware proof.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import struct
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

SDK = Path(__file__).resolve().parents[1]
ROOT = SDK.parents[2]


def function(text, name):
    start = text.index(name)
    start = text.rfind("\n", 0, start) + 1
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    inc = out / "include"
    inc.mkdir(parents=True, exist_ok=True)
    (inc / "module_base_kernel_func_hook.h").write_text("#pragma once\n")
    (inc / "kernel_module_kit_umbrella.h").write_text(r'''#pragma once
#include <algorithm>
#include "aarch64_asm_helper.h"
#include "module_err_def.h"
#include "module_base_kernel_mem.h"
namespace kernel_module {
using HookHandle = struct HookContext*;
void arm64_before_hook_start(asmjit::a64::Assembler*);
void arm64_before_hook_end(asmjit::a64::Assembler*,bool);
void arm64_emit_call_original(asmjit::a64::Assembler*);
bool is_kernel_version_less(const std::string&);
KModErr install_kernel_function_before_hook(uint64_t,const std::vector<uint8_t>&,HookHandle*);
}
''')
    (inc / "maplesu_request_identity.h").write_text(r'''#pragma once
#include "maplesu_request_private.h"
namespace kernel_module {
struct RequestIdentity {
 uint64_t auth_state = 1;
 bool capture(){return true;}
 bool matches() const {return true;}
};
}
''')
    hook = (SDK / "src/func_hook/module_base_kernel_func_hook.cpp").read_text(encoding="utf-8")
    anchors = "\n".join(re.findall(r"static char k(?:OriginKaddrSlot|RetModeSlot|MyBeforeHookBegin|MyBeforeHookEnd)Flag\[64\] =\s*\n\s*\"[^\"]+\";", hook))
    names = ["find_origin_kaddr_slot_offset", "find_ret_insn_slot_offset", "arm64_before_hook_start", "arm64_before_hook_end", "arm64_emit_call_original"]
    bodies = "\n".join(function(hook, name + "(") for name in names)
    emission = '#include "kernel_module_kit_umbrella.h"\n#include "' + (SDK / "src/aarch64/branch_encoding.h").as_posix() + '"\nnamespace kernel_module {\n' + anchors + "\nconstexpr uint32_t kAnchorNeedleSize=56;\n" + bodies + "\n}\n"
    (out / "emission.cpp").write_text(emission, encoding="utf-8")
    root_source=(SDK/"src/module/idx070/module_root.cpp").read_text(encoding="utf-8")
    (out/"generated_staging.h").write_text('#include "maplesu_request_identity.h"\n'+
        function(root_source,"struct GeneratedActorStaging")+';\n',encoding="utf-8")
    (out / "probe.cpp").write_text(r'''#include "kernel_module_kit_umbrella.h"
#include "maplesu_hook_private.h"
#include "core01/patch_mtk_hbt_filldir64.h"
#include "generated_staging.h"
#include <fstream>
#include <cassert>
#include <cstring>
namespace {
constexpr uint64_t target=0xffff000000200000ULL,allocation=0xffff000000300000ULL;
std::vector<uint8_t> uploaded,callback;
int fail=0,writes=0,frees=0,allocs=0,protects=0,closes=0;
bool uncertain=false,free_error=false;
uint64_t mode=1;
void save(const char* path,const std::vector<uint8_t>& v){std::ofstream f(path,std::ios::binary);f.write((const char*)v.data(),v.size());}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state(){return mode;}
RequestManagementReply maplesu_request_management_reply(){RequestManagementReply r{};r.completed=!uncertain;return r;}
CleanupReceiptScope::CleanupReceiptScope() noexcept {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept {}
HookCleanupReceiptScope::HookCleanupReceiptScope() noexcept {}
HookCleanupReceiptScope::~HookCleanupReceiptScope() noexcept {}
RequestManagementReply maplesu_hook_reply(){return maplesu_request_management_reply();}
KModErr maplesu_hook_close(){++closes;return KModErr::OK;}
bool is_kernel_version_less(const std::string&){return false;}
KModErr maplesu_hook_legacy_boot_selected(bool& selected){selected=false;return KModErr::OK;}
KModErr request_kernel_alloc_mem(uint32_t,uint64_t&){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr install_kernel_function_before_hook(uint64_t,const std::vector<uint8_t>&,HookHandle*){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr read_kernel_mem(uint64_t p,void* b,uint32_t n){assert(p==target-4 && n==4);uint32_t t=0x12345678;std::memcpy(b,&t,4);return fail==1?KModErr::ERR_MODULE_ASM:KModErr::OK;}
KModErr alloc_kernel_mem(uint32_t,uint64_t&){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr maplesu_hook_reserve_code(uint32_t n,uint64_t,uint64_t& p){++allocs;if(fail==2)return KModErr::ERR_NO_MEM;p=allocation;uploaded.resize(n);return KModErr::OK;}
KModErr write_kernel_mem(uint64_t p,const void* b,uint32_t n,KernMemProt){++writes;if(fail==writes+2)return KModErr::ERR_MODULE_ASM;std::memcpy(uploaded.data()+p-allocation,b,n);return KModErr::OK;}
KModErr maplesu_hook_release(uint64_t p){assert(p==allocation);++frees;return free_error?KModErr::ERR_MODULE_ASM:KModErr::OK;}
KModErr free_kernel_mem(uint64_t p){return maplesu_hook_release(p);}
KModErr maplesu_hook_write(uint64_t p,const void* b,uint32_t n){return write_kernel_mem(p,b,n,KernMemProt::KMP_RW);}
KModErr set_kernel_mem_protection(uint64_t,uint64_t,KernMemProt){++protects;return KModErr::OK;}
KModErr icache_invalidate_all_cpus(){return KModErr::OK;}
KModErr maplesu_install_owned_hook_with_actor(uint64_t,const void* p,uint32_t n,const OwnedHookActor& a,bool& transferred,HookHandle*){
 assert(a.allocation==allocation && a.target==target && a.kcfi==0x12345678); callback.assign((const uint8_t*)p,(const uint8_t*)p+n);transferred=true;return KModErr::OK;
}
}
namespace default_modules::module_root {
PatchBase::PatchBase(uint32_t,uint32_t){}
PatchBase::PatchBase(const PatchBase&){}
PatchBase::~PatchBase(){}
KModErr PatchBase::patch_kernel_before_hook(uint64_t,const asmjit::a64::Assembler*){return KModErr::ERR_MODULE_ASM;}
class PatchIterateDir : public PatchBase {
public:
 PatchIterateDir(const PatchBase&,uint64_t);
 ~PatchIterateDir();
 KModErr patch_iterate_dir_owned(uint64_t,uint64_t,const kernel_module::OwnedHookActor&,bool&);
private: uint64_t address;
};
}
int main(int argc,char**argv){
 assert(argc==2);using namespace default_modules::module_root;PatchBase b(0,0);PatchMtkHbtFilldir64 actor(b,target);
 {GeneratedActorStaging s;s.actor.allocation=allocation;}assert(frees==1 && closes==0);frees=0;
 {GeneratedActorStaging s;s.actor.allocation=allocation;s.transferred=true;}assert(frees==0 && closes==0);
 uncertain=true;{GeneratedActorStaging s;s.actor.allocation=allocation;}assert(frees==0 && closes==1);uncertain=false;closes=0;
 free_error=true;{GeneratedActorStaging s;s.actor.allocation=allocation;}assert(frees==1 && closes==1);free_error=false;closes=frees=0;
 for(fail=1;fail<=4;++fail){writes=frees=allocs=0;uint64_t output=77;kernel_module::OwnedHookActor owned{};
  assert(actor.generate_hook_fake_filldir64_owned({"hidden"},output,owned)!=KModErr::OK);
  assert(output==77 && owned.allocation==0 && frees==(fail>=3));
 }
 fail=writes=frees=allocs=0;uint64_t output=0;kernel_module::OwnedHookActor owned{};
 assert(actor.generate_hook_fake_filldir64_owned({"hidden"},output,owned)==KModErr::OK);
 assert(output==allocation+4 && owned.bytes==uploaded.size() && frees==0 && protects==0);
 uint32_t word=0;std::memcpy(&word,uploaded.data()+4,4);assert(word==0xd503245f);
 const std::string dir=argv[1];save((dir+"/actor.bin").c_str(),uploaded);
 PatchIterateDir patch(b,target+0x10000);bool transferred=false;
 assert(patch.patch_iterate_dir_owned(target,output,owned,transferred)==KModErr::OK && transferred);
 save((dir+"/callback.bin").c_str(),callback);
 std::cout<<"staging fault cases 4; owned staging, BTI and handoff passed\n";
}
'''.replace("KModErr PatchBase::patch_kernel_before_hook(uint64_t,const asmjit::a64::Assembler*){return KModErr::ERR_MODULE_ASM;}", ""), encoding="utf-8")
    # PatchBase's legacy helper exists only in m077's private declaration.
    legacy = '\nnamespace default_modules::module_root { KModErr PatchBase::patch_kernel_before_hook(uint64_t,const asmjit::a64::Assembler*){return KModErr::ERR_MODULE_ASM;} }\n'
    source77 = SDK / "src/patch/patch_iterate_dir.cpp"
    captured77 = out / "iterate.cpp"
    captured77.write_text(source77.read_text(encoding="utf-8") + legacy, encoding="utf-8")
    cxx = next((ROOT / ".research/toolchains").glob("llvm-mingw-*/bin/x86_64-w64-mingw32-clang++.exe"))
    lib = ROOT / "docs/dual-mode/impl/_work/2026-11-15-p1/evidence/relocation/fixed-host/asmjit.a"
    command = [str(cxx), "-std=c++20", "-O1", "-DASMJIT_STATIC", "-DASMJIT_NO_X86", "-DASMJIT_NO_COMPILER", "-DASMJIT_NO_BUILDER", "-include", "algorithm", "-I"+str(inc), "-I"+str(SDK.parent/"include"), "-I"+str(SDK/"include"), "-I"+str(SDK/"third_party/asmjit/1.16.0/src"), str(out/"probe.cpp"), str(out/"emission.cpp"), str(captured77), str(SDK/"src/patch/patch_mtk_hbt_filldir64.cpp"), str(lib), "-static", "-o", str(out/"probe.exe")]
    for name, argv in [("compile",command),("staging",[str(out/"probe.exe"),str(out)])]:
        p=subprocess.run(argv,capture_output=True,text=True,encoding="utf-8",errors="replace")
        (out/(name+".log")).write_text(p.stdout+p.stderr,encoding="utf-8")
        if p.returncode: raise RuntimeError(name+": "+p.stderr[-6000:]+p.stdout[-1000:])
    callback=bytearray((out/"callback.bin").read_bytes())
    actor=(out/"actor.bin").read_bytes()
    origin=b"mAmLFv9WvjgL8PK5mhoJzEHFtFY44Q3uzngXEcUBTxk1QnBGjGS4Dea7jffSewf"[:56]
    ret=b"ntfvxVpMRtNPHCMH57mAikEvbAFNG6hgZhIwaf1ANev5CeCPbAHMplHW3DcUoVa"[:56]
    base,original,stop,ctx,stack=0x100000,0x200000,0x180000,0x300000,0x410000
    oslot,rslot=callback.index(origin)+56,callback.index(ret)+56
    struct.pack_into("<Q",callback,oslot,original)
    struct.pack_into("<I",callback,rslot,0x14000000 | (((stop-base-rslot)//4)&0x3ffffff))
    results=[]
    for name,context,old_actor in [("matched",ctx,0xffff000000200000),("unmatched",ctx,0x9988),("null",0,0)]:
        uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        for p,n in [(base,0x100000),(original,0x1000),(ctx,0x1000),(0x400000,0x20000)]:uc.mem_map(p,n)
        uc.mem_write(base,bytes(callback));uc.mem_write(original,struct.pack("<II",0xa8c107e0,0xd65f03c0))
        uc.mem_write(ctx,struct.pack("<Q",old_actor));uc.reg_write(UC_ARM64_REG_SP,stack-112)
        uc.mem_write(stack-112,struct.pack("<QQQQ",0x2828,0,0x2929,stop))
        for reg,val in [(UC_ARM64_REG_X0,0xabc),(UC_ARM64_REG_X1,context),(UC_ARM64_REG_X19,0x1919),(UC_ARM64_REG_X20,0x2020),(UC_ARM64_REG_X21,0x2121)]:uc.reg_write(reg,val)
        calls=[]
        def observe(u,address,size,data):
            if address==original+4:
                expected=0xffff000000300004 if name=="matched" else old_actor
                assert struct.unpack("<Q",u.mem_read(ctx,8))[0]==expected
                assert u.reg_read(UC_ARM64_REG_X0)==0xabc and u.reg_read(UC_ARM64_REG_X1)==context
                calls.append(1);u.reg_write(UC_ARM64_REG_X0,0x55)
                for reg in [UC_ARM64_REG_X1,UC_ARM64_REG_X10,UC_ARM64_REG_X11]:u.reg_write(reg,0xdead)
        uc.hook_add(UC_HOOK_CODE,observe);uc.emu_start(base,stop,count=1000)
        assert calls==[1] and uc.reg_read(UC_ARM64_REG_SP)==stack
        assert struct.unpack("<Q",uc.mem_read(ctx,8))[0]==old_actor
        assert uc.reg_read(UC_ARM64_REG_X0)==0x55
        for reg,val in [(UC_ARM64_REG_X19,0x1919),(UC_ARM64_REG_X20,0x2020),(UC_ARM64_REG_X21,0x2121),(UC_ARM64_REG_X28,0x2828),(UC_ARM64_REG_X29,0x2929)]:assert uc.reg_read(reg)==val
        results.append(name)
    actor_results=[]
    for name in [b"hidden",b"public"]:
        uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        uc.mem_map(0x500000,0x1000);uc.mem_write(0x500000,actor[4:])
        uc.mem_map(ctx,0x1000);uc.mem_write(ctx,name+b"\0")
        uc.mem_map(stop,0x1000);uc.mem_map(0xffff000000200000,0x1000)
        uc.reg_write(UC_ARM64_REG_X0,0xabc);uc.reg_write(UC_ARM64_REG_X1,ctx)
        uc.reg_write(UC_ARM64_REG_X2,len(name));uc.reg_write(UC_ARM64_REG_X30,stop)
        fallback=[]
        def actor_observe(u,address,size,data):
            if address==0xffff000000200000:
                assert name==b"public" and u.reg_read(UC_ARM64_REG_X16)==address
                assert u.reg_read(UC_ARM64_REG_X0)==0xabc
                assert u.reg_read(UC_ARM64_REG_X1)==ctx and u.reg_read(UC_ARM64_REG_X2)==len(name)
                fallback.append(1);u.emu_stop()
        uc.hook_add(UC_HOOK_CODE,actor_observe);uc.emu_start(0x500000,stop,count=1000)
        if name==b"hidden":assert not fallback and uc.reg_read(UC_ARM64_REG_X0)==1
        else:assert fallback==[1]
        actor_results.append(name.decode())
    (out/"results.json").write_text(json.dumps(dict(stagingFaults=4,handoffCleanup=4,emittedCallback=results,emittedActor=actor_results,passed=True),indent=2)+"\n")
    print("4 staging failures, 4 handoff cleanup, 3 real callback paths and 2 real actor paths passed")

if __name__=="__main__":main()
