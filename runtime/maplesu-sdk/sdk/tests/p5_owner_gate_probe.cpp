// Production m029 installer/gate driver. Request/resident functions are API fixtures.
#include "kernel_module_kit_umbrella.h"
#include "maplesu_hook_private.h"
#include "publication_return_private.h"
#include "legacy_hook_private.h"
#include "../src/aarch64/branch_encoding.h"
#include "module_base_kernel_func_hook_stack.h"
#include <cassert>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <map>
#include <set>
#include <string>
#include <vector>
#include <cstdlib>
#undef assert
#define assert(condition) do { if (!(condition)) { std::cerr << "assertion failed: " #condition << " at line " << __LINE__ << '\n'; std::exit(3); } } while (false)
namespace kernel_module {
struct HookContext { uint64_t entry; uint32_t original; uint64_t trampoline,size; uint8_t enabled; uint64_t disable_flag; };
void arm64_before_hook_start(asmjit::a64::Assembler*);
void arm64_before_hook_end(asmjit::a64::Assembler*,bool);
void arm64_after_hook_start(asmjit::a64::Assembler*);
void arm64_after_hook_end(asmjit::a64::Assembler*);
KModErr install_kfunc_before_hook_with_buf(uint64_t,const void*,uint32_t,HookContext**);
KModErr install_kfunc_after_hook_with_buf(uint64_t,const void*,uint32_t,HookContext**);
KModErr uninstall_kernel_hook(HookContext*);
KModErr maplesu_install_owned_hook_with_shared_bools(uint64_t,const void*,uint32_t,bool,const RequestSharedBool*,uint32_t,HookContext**);
}
using namespace kernel_module;
extern "C" int maplesu_get_pending_hook(HookContext*) noexcept;
extern "C" int maplesu_get_after_hook_pool(HookContext*,uint64_t*,uint32_t*) noexcept;
extern "C" const char* __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(){return "fixture-key";}
extern "C" int maplesu_identity_fork_status() noexcept { return 0; }
namespace fixture {
constexpr uint64_t base=UINT64_C(0xffff000000000000), entry_root=base+0x300000,
 gate=base+0x400000, payload=base+0x500000, pool=base+0x700000,
 control=base+0x800000, enter_function=base+0x810000, leave_function=base+0x820000;
constexpr uint64_t id=0x1234;
uint64_t entry=entry_root;
uint32_t expected_instruction=0xd503201f;
std::map<uint64_t,uint8_t> memory;
std::map<uint64_t,uint32_t> staging;
RequestManagementReply last{};
std::string scenario;
unsigned reserves=0,allocations=0,prepares=0,publishes=0,disables=0,retires=0,closes=0,frees=0,writes=0;
std::vector<RequestSharedBool> dependencies;
bool use_after=false,legacy=false;
bool prepared=false,published=false,retired=false,close_seen=false;
uint32_t payload_bytes=0,source_bytes=0;
std::vector<std::string> calls;
void reply(uint32_t command, bool completed=true, uint32_t effects=0, int32_t status=0) {
 last={};last.command=command;last.completed=completed;last.effects=effects;last.status=status;
}
void put(uint64_t address,const void* data,uint32_t count) {
 const auto* bytes=static_cast<const uint8_t*>(data);for(uint32_t n=0;n<count;++n)memory[address+n]=bytes[n];
}
void word(uint64_t address,uint32_t value){put(address,&value,4);}
void dump(uint64_t address,uint32_t count) {
 std::cout<<"code "<<std::hex<<address<<" ";
 for(uint32_t n=0;n<count;++n)std::cout<<std::setw(2)<<std::setfill('0')<<unsigned(memory[address+n]);
 std::cout<<"\n";
}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state(){return fixture::legacy?4:1;}
KModErr maplesu_kernel_metadata_v2(maplesu_metadata_v2&,uint64_t& address,bool& supported){
 assert(fixture::legacy);address=fixture::base+0xa00000;supported=fixture::scenario!="select-unsupported";
 if(fixture::scenario=="select-metadata-failure")return KModErr::ERR_MODULE_TEST_CHANNEL;
 if(fixture::scenario=="select-overflow")address=UINT64_MAX;
 return KModErr::OK;
}
KModErr maplesu_request_v2_read(uint64_t address,void* output,uint32_t bytes){
 assert(fixture::legacy);
 if(address==fixture::base+0xa00000+sizeof(maplesu_metadata_v2)){
  assert(bytes==sizeof(maplesu_legacy_hook_metadata_private));
  if(fixture::scenario=="select-read-failure")return KModErr::ERR_MODULE_NOT_KADDR;
  maplesu_legacy_hook_metadata_private tail{MAPLESU_LEGACY_HOOK_METADATA_MAGIC,MAPLESU_LEGACY_HOOK_SCHEMA,sizeof(tail),0x10000,0x20000};
  if(fixture::scenario=="select-boot")tail={};
  if(fixture::scenario=="select-corrupt")tail.magic^=1;
  std::memcpy(output,&tail,bytes);return KModErr::OK;
 }
 return read_kernel_mem(address,output,bytes);
}
RequestManagementReply maplesu_request_management_reply(){return fixture::last;}
KModErr maplesu_request_owner_shared_bool_create(bool,RequestSharedBool&){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr maplesu_request_owner_shared_bool_store(const RequestSharedBool&,bool){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr maplesu_request_owner_shared_bool_release(const RequestSharedBool&){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr read_kernel_mem(uint64_t address,void* data,uint32_t count){auto* p=static_cast<uint8_t*>(data);for(uint32_t n=0;n<count;++n)p[n]=fixture::memory[address+n];return KModErr::OK;}
KModErr write_kernel_mem(uint64_t address,const void* data,uint32_t count,KernMemProt protection){assert(protection==KernMemProt::KMP_RW);++fixture::writes;fixture::put(address,data,count);return KModErr::OK;}
KModErr set_kernel_mem_protection(uint64_t,uint64_t,KernMemProt){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr icache_invalidate_all_cpus(){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr acquire_kernel_idle_region(uint64_t,uint64_t&){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr acquire_kernel_hook_disable_flag(uint64_t&){assert(false);return KModErr::ERR_MODULE_ASM;}
void arm64_module_asm_func_start(asmjit::a64::Assembler* a){a->stp(asmjit::a64::x29,asmjit::a64::x30,asmjit::a64::ptr(asmjit::a64::sp).pre(-16));}
void arm64_module_asm_func_end(asmjit::a64::Assembler* a,asmjit::a64::GpX result){a->mov(asmjit::a64::x0,result);a->ldp(asmjit::a64::x29,asmjit::a64::x30,asmjit::a64::ptr(asmjit::a64::sp).post(16));a->ret(asmjit::a64::x30);}
KModErr execute_kernel_asm_func(const std::vector<uint8_t>& code,uint64_t& result);
KModErr fill00_kernel_mem(uint64_t,uint32_t,KernMemProt){assert(false);return KModErr::ERR_MODULE_ASM;}
KModErr alloc_kernel_mem(uint32_t size,uint64_t& address){
 using namespace fixture;++allocations;calls.push_back("ALLOC");address=pool;staging[address]=size;
 reply(MAPLESU_REQUEST_ALLOC,true,MAPLESU_EFFECT_ALLOCATED);return KModErr::OK;
}
KModErr free_kernel_mem(uint64_t address){
 using namespace fixture;++frees;calls.push_back("RELEASE");assert(!prepared&&!published);
 assert(staging.erase(address)==1);reply(MAPLESU_REQUEST_RELEASE,true,MAPLESU_EFFECT_RELEASED);return KModErr::OK;
}
KModErr maplesu_request_owner_reserve_code(uint32_t size,uint64_t near,uint64_t& address){
 using namespace fixture;++reserves;calls.push_back("ALLOC_CODE");
 const uint64_t actual=reserves==1?gate:payload;assert(reserves<=2);
 assert((reserves==1&&near==entry)||(reserves==2&&near==0));staging[actual]=size;
 if(reserves==2)payload_bytes=size;
 const bool lost=(scenario=="alloc-lost-gate"&&reserves==1)||(scenario=="alloc-lost-payload"&&reserves==2);
 reply(MAPLESU_REQUEST_ALLOC,!lost,MAPLESU_EFFECT_ALLOCATED);
 if(lost)return KModErr::ERR_MODULE_TEST_CHANNEL;
 address=actual;return KModErr::OK;
}
KModErr maplesu_request_owner_publication_prepare(const RequestPublicationMember* members,uint32_t count,
 uint32_t gate_index,uint64_t target,uint64_t offset,uint32_t expected,uint64_t target_payload,RequestPublication& publication){
 using namespace fixture;++prepares;calls.push_back("PREPARE");assert(prepares==1);
 const uint32_t local_count=2;
 assert(count==local_count+dependencies.size()&&count<=64);assert(gate_index==0&&target==entry&&offset==0&&expected==expected_instruction&&target_payload==payload);
 assert(members[0].address==gate&&members[0].role==MAPLESU_PUBLICATION_GATE_CODE);
 assert(members[1].address==payload&&members[1].role==MAPLESU_PUBLICATION_PAYLOAD_CODE);
 for(uint32_t n=0;n<local_count;++n){assert(staging.count(members[n].address));assert(!members[n].shared_bool.id);}
 std::set<uint64_t> ids;
 for(uint32_t n=0;n<dependencies.size();++n){
  const auto& member=members[local_count+n];const auto& expected=dependencies[n];
  assert(member.role==MAPLESU_PUBLICATION_SHARED_BOOL&&member.address==expected.address);
  assert(member.shared_bool.id==expected.id&&member.shared_bool.address==expected.address);
  assert(member.shared_bool.host.low==expected.host.low&&member.shared_bool.host.high==expected.host.high&&member.shared_bool.epoch==expected.epoch);
  assert(ids.insert(member.shared_bool.id).second);
 }
 prepared=true;
 const bool lost=scenario=="prepare-lost";reply(MAPLESU_REQUEST_PUBLICATION_PREPARE,!lost,MAPLESU_EFFECT_PUBLICATION_PREPARED);
 if(lost)return KModErr::ERR_MODULE_TEST_CHANNEL;
 publication.id=id;publication.control_address=control;return KModErr::OK;
}
KModErr maplesu_request_owner_publication_control(const RequestPublication& publication,maplesu_publication_control_v3& value){
 using namespace fixture;calls.push_back("CONTROL");assert(prepared&&publication.id==id);
 value={};value.epoch=1;value.publication_id=id;value.enter_function=enter_function;value.leave_function=leave_function;
 value.payload_entry=payload;value.entry_address=entry;value.resume_address=entry+4;value.schema=1;value.length=sizeof(value);
 if(scenario=="control-lost"){reply(MAPLESU_REQUEST_READ,false);return KModErr::ERR_MODULE_TEST_CHANNEL;}
 maplesu_publication_return_private tail{};tail.publication_id=id;tail.schema=MAPLESU_PRIVATE_RETURN_CONTROL_SCHEMA;tail.length=sizeof(tail);
 tail.acquire_function=base+0x830000;tail.release_function=base+0x840000;
 tail.caller_x28_function=base+0x850000;tail.caller_lr_function=base+0x860000;
 tail.status_function=base+0x870000;
 tail.actor_attach_function=base+0x880000;tail.payload_function=base+0x890000;
 if(scenario=="after-tail-id")++tail.publication_id;
 if(scenario=="after-tail-length")--tail.length;
 put(control,&value,sizeof(value));put(control+64,&tail,sizeof(tail));
 reply(MAPLESU_REQUEST_READ);return KModErr::OK;
}
KModErr maplesu_request_owner_publication_publish(const RequestPublication& publication){
 using namespace fixture;++publishes;calls.push_back("PUBLISH");assert(publishes==1&&prepared&&publication.id==id);
 uint32_t branch=0;assert(maplesu::aarch64::encode_b(entry,gate,branch));word(entry,branch);published=true;
 const bool lost=scenario=="publish-lost";
 const bool logical=scenario=="before-logical-publish"||scenario=="after-logical-publish"||scenario.find("brk")!=std::string::npos;
 const uint32_t effects=MAPLESU_EFFECT_PUBLICATION_PUBLISHED|MAPLESU_EFFECT_PUBLICATION_RETAINED;
 const uint32_t text=MAPLESU_EFFECT_TEXT_ATTEMPTED|MAPLESU_EFFECT_WRITTEN;
 reply(MAPLESU_REQUEST_PUBLICATION_PUBLISH,!lost,effects|(logical?0:text));last.result=logical?0:4;
 if(scenario=="before-invalid-publish-0-text")last.result=0;
 if(scenario=="before-invalid-publish-4-no-text")last.effects=effects;
 if(scenario=="before-invalid-publish-0-pending"){last.result=0;last.effects=effects|MAPLESU_EFFECT_PENDING;}
 if(lost||scenario=="pending-null")return KModErr::ERR_MODULE_TEST_CHANNEL;
 return KModErr::OK;
}
KModErr maplesu_request_owner_publication_disable(const RequestPublication& publication){
 using namespace fixture;++disables;calls.push_back("DISABLE");assert(publication.id==id&&published&&!retired);
 reply(MAPLESU_REQUEST_PUBLICATION_DISABLE,true,MAPLESU_EFFECT_PUBLICATION_RETAINED);return KModErr::OK;
}
KModErr maplesu_request_owner_publication_retire(const RequestPublication& publication){
 using namespace fixture;++retires;calls.push_back("RETIRE");assert(publication.id==id&&prepared);
 if(scenario=="pending-null"&&retires==1){reply(MAPLESU_REQUEST_PUBLICATION_RETIRE,true,MAPLESU_EFFECT_PENDING,MAPLESU_REQUEST_STATUS_IO);return KModErr::ERR_MODULE_ASM;}
 word(entry,0xd503201f);retired=true;published=false;staging.clear();
 reply(MAPLESU_REQUEST_PUBLICATION_RETIRE,true,MAPLESU_EFFECT_RELEASED);return KModErr::OK;
}
KModErr maplesu_request_owner_close(){
 using namespace fixture;++closes;calls.push_back("CLOSE");close_seen=true;
 if(!published){staging.clear();if(prepared)retired=true;}
 reply(MAPLESU_REQUEST_SESSION_CLOSE,true,MAPLESU_EFFECT_SESSION_CLOSED);return KModErr::OK;
}
}
int main(int argc,char** argv){
 using namespace fixture;scenario=argc>1?argv[1]:"before-continue";
 if(scenario.rfind("legacy-",0)==0){legacy=true;scenario.erase(0,7);}
 if(scenario.rfind("select-",0)==0){
  bool selected=true;
  const auto error=maplesu_hook_legacy_boot_selected(selected);
  auto expected=KModErr::OK;
  if(legacy&&scenario=="select-metadata-failure")expected=KModErr::ERR_MODULE_TEST_CHANNEL;
  if(legacy&&(scenario=="select-overflow"||scenario=="select-read-failure"))expected=KModErr::ERR_MODULE_NOT_KADDR;
  assert(error==expected&&selected==(legacy&&scenario=="select-boot"));
  assert(!reserves&&!prepares&&!publishes&&!writes);
  std::cout<<"PASS "<<scenario<<" selected="<<selected<<" error="<<to_num(error)<<"\n";
  return 0;
 }
 reply(MAPLESU_REQUEST_SESSION_OPEN);word(entry,0xd503201f);word(entry+4,0x91000400);word(entry+8,0xd65f03c0);
 uint32_t pac=0;
 if(scenario.find("pac")!=std::string::npos){
  assert(argc==5);const uint32_t first=std::stoul(argv[2],nullptr,16),second=std::stoul(argv[3],nullptr,16);
  pac=std::stoul(argv[4],nullptr,16);word(entry_root,first);word(entry_root+4,second);entry+=4;expected_instruction=second;
 }
 auto context=init_aarch64_asm();auto* a=context.assembler();using namespace asmjit;using namespace asmjit::a64;
 const bool after=scenario.rfind("after",0)==0;use_after=after;
 if(after){arm64_after_hook_start(a);aarch64_asm_mov_x(a,x9,UINT64_C(0x1122334455667788));
  for(int offset=16;offset<96;offset+=16)a->stp(x9,x9,ptr(sp,offset));a->add(x0,x0,Imm(100));arm64_after_hook_end(a);
 }else{arm64_before_hook_start(a);if(scenario=="before-early-pad")a->nop();
  aarch64_asm_mov_x(a,x9,UINT64_C(0x1122334455667788));
  for(int offset=16;offset<96;offset+=16)a->stp(x9,x9,ptr(sp,offset));
  if(scenario=="before-manual"){
   a->stp(x29,x30,ptr(sp).pre(-16));
   arm64_emit_call_original_with_stack(a,{112,24*8});
   a->ldp(x29,x30,ptr(sp).post(16));
  }
  a->add(x0,x0,Imm(100));
  arm64_before_hook_end(a,scenario.find("early")==std::string::npos&&scenario!="before-manual");
 }
 auto bytes=aarch64_asm_to_bytes(a);source_bytes=bytes.size();assert(!bytes.empty());
 const bool shared=scenario.find("shared")!=std::string::npos;
 const bool invalid=scenario.find("invalid-")!=std::string::npos;
 if(scenario.find("brk")!=std::string::npos)word(entry,0xd4200080);
 if(shared){
  uint32_t amount=scenario.find("max")!=std::string::npos?62:2;
  if(scenario.find("too-many")!=std::string::npos)++amount;
  for(uint32_t n=0;n<amount;++n){RequestSharedBool item{};item.id=0x300+n;item.address=base+0x900000+n*8;item.host.low=0x88776655;item.host.high=0x11223344;item.epoch=9;dependencies.push_back(item);}
  if(scenario.find("duplicate")!=std::string::npos)dependencies[1].id=dependencies[0].id;
  if(scenario.find("zero-id")!=std::string::npos)dependencies[0].id=0;
  if(scenario.find("user-address")!=std::string::npos)dependencies[0].address=0x10000;
  if(scenario.find("zero-host")!=std::string::npos)dependencies[0].host={};
  if(scenario.find("zero-epoch")!=std::string::npos)dependencies[0].epoch=0;
 }
 HookContext* handle=nullptr;
 const auto error=shared?maplesu_install_owned_hook_with_shared_bools(entry_root,bytes.data(),bytes.size(),after,
  scenario.find("null")!=std::string::npos?nullptr:dependencies.data(),dependencies.size(),&handle):after?install_kfunc_after_hook_with_buf(entry_root,bytes.data(),bytes.size(),&handle):
  install_kfunc_before_hook_with_buf(entry_root,bytes.data(),bytes.size(),scenario=="pending-null"?nullptr:&handle);
 const auto lost_error=legacy?KModErr::ERR_MODULE_NOT_KADDR:KModErr::ERR_MODULE_TEST_CHANNEL;
 if(scenario.find("invalid-publish")!=std::string::npos){
  assert(error==KModErr::ERR_MODULE_ASM&&!handle&&prepares==1&&publishes==1&&retires==1&&retired);
 }else if(scenario=="after-tail-id"||scenario=="after-tail-length"){
 assert(error!=KModErr::OK&&!handle&&prepares==1&&!publishes&&retires==1);
 }else if(invalid){
  assert(error==KModErr::ERR_MODULE_PARAM&&!handle&&!reserves&&!allocations&&!prepares&&!publishes&&!writes&&!frees);
 }else if(scenario.find("brk")!=std::string::npos&&(scenario.find("missing")!=std::string::npos||scenario.find("fault")!=std::string::npos||scenario.find("mismatch")!=std::string::npos)){
  assert(error!=KModErr::OK&&!handle&&!reserves&&!allocations&&!prepares&&!publishes&&!writes&&!frees);
 }else if(scenario=="alloc-lost-gate"||scenario=="alloc-lost-payload"){
  assert(error==lost_error&&!handle&&!prepares&&!publishes&&closes==1&&!frees&&staging.empty());
 }else if(scenario=="prepare-lost"){
  assert(error==lost_error&&!handle&&prepares==1&&!publishes&&closes==1&&!retires&&!frees&&staging.empty());
 }else if(scenario=="control-lost"){
  assert(error==lost_error&&!handle&&prepares==1&&!publishes&&closes==1&&!retires&&!frees&&staging.empty());
 }else if(scenario=="publish-lost"){
  assert(error==lost_error&&!handle&&publishes==1&&closes==1&&retires==1&&retired&&!published&&!frees);
 }else if(scenario=="pending-null"){
  assert(error==KModErr::ERR_MODULE_TEST_CHANNEL&&!handle&&publishes==1&&retires==1&&!closes&&published);
  HookContext pending{};assert(maplesu_get_pending_hook(&pending)==1&&pending.disable_flag==control);
  assert(uninstall_kernel_hook(&pending)==KModErr::OK&&!pending.enabled&&retired&&retires==2&&publishes==1);
  HookContext absent{};assert(!maplesu_get_pending_hook(&absent));
 }else{
  assert(error==KModErr::OK&&handle&&handle->enabled&&prepares==1&&publishes==1&&!closes&&!frees);
  if(scenario=="before-switch-protocol-uninstall"){
   legacy=!legacy;
   assert(uninstall_kernel_hook(handle)==KModErr::ERR_MODULE_ROOT_KEY&&!disables&&!retires&&published);
  }else if(shared){
   assert(uninstall_kernel_hook(handle)==KModErr::OK&&retired&&retires==1&&disables==1);
  }else if(scenario=="after-close-uninstall"||scenario=="before-close-uninstall"){
   assert(maplesu_hook_close()==KModErr::OK&&published&&staging.size()==2u);
   assert(uninstall_kernel_hook(handle)==KModErr::OK&&!handle->enabled&&disables==1&&retires==1&&retired&&!published);
  }else{
   std::cout<<"meta "<<std::hex<<entry<<" "<<gate<<" "<<payload<<" "<<pool<<" "<<control<<" "<<enter_function<<" "<<leave_function<<" "<<source_bytes<<" "<<payload_bytes<<" "<<entry_root<<" "<<pac<<"\n";
   dump(entry_root,20);dump(gate,4096);dump(payload,payload_bytes);
  }
 }
 std::cout<<"PASS "<<scenario<<" prepare="<<prepares<<" publish="<<publishes<<" close="<<closes<<" disable="<<disables<<" retire="<<retires<<" free="<<frees<<"\n";
 for(const auto& call:calls)std::cout<<"call "<<call<<"\n";
}
namespace kernel_module {
CleanupReceiptScope::CleanupReceiptScope() noexcept : saved_errno_(errno), saved_process_(0), saved_reply_(fixture::last) {}
CleanupReceiptScope::~CleanupReceiptScope() noexcept { fixture::last=saved_reply_;errno=saved_errno_; }
KModErr maplesu_resolve_kprobe_fields(KernelFieldDescription& addr,KernelFieldDescription& opcode,bool& blob){addr={0,8,16};opcode={8,4,16};blob=true;return KModErr::OK;}
KModErr kallsyms_lookup_name(const char* name,uint64_t& address){assert(std::string(name)=="kprobe_mutex");address=0xffff000000960000ULL;return KModErr::OK;}
}

namespace kernel_module {
KModErr execute_kernel_asm_func(const std::vector<uint8_t>& code,uint64_t& result){
 using namespace fixture;
 if(!legacy){
  assert(scenario.find("brk")!=std::string::npos);
  std::cout<<"snapshot ";for(uint8_t b:code)std::cout<<std::hex<<std::setw(2)<<std::setfill('0')<<unsigned(b);std::cout<<"\n";
  result=scenario.find("missing")!=std::string::npos||scenario.find("fault")!=std::string::npos||scenario.find("mismatch")!=std::string::npos?0:UINT64_C(0x1d503201f);
  return KModErr::OK;
 }
 uint64_t regs[32]{};maplesu_management_v3 op{};bool before_get=true;
 for(size_t offset=0;offset<code.size();offset+=4){uint32_t w;std::memcpy(&w,code.data()+offset,4);
  const unsigned reg=w&31,shift=((w>>21)&3)*16;const uint64_t imm=(w>>5)&0xffff;
  if((w&0xff800000u)==0xd2800000u)regs[reg]=imm<<shift;
  else if((w&0xff800000u)==0xf2800000u)regs[reg]=(regs[reg]&~(UINT64_C(0xffff)<<shift))|(imm<<shift);
  if(before_get&&(w&0xffc003ffu)==0xf90003eau){unsigned at=((w>>10)&0xfff)*8;if(at<64)std::memcpy(reinterpret_cast<uint8_t*>(&op)+at,&regs[10],8);}
  if((w&0xfffffc1fu)==0xd63f0000u)before_get=false;
 }
 const uint32_t cmd=regs[1];const uint64_t destination=regs[0];assert(destination&&cmd);
 KModErr error=KModErr::OK;
 if(cmd==MAPLESU_REQUEST_SESSION_OPEN){reply(cmd);last.result=0x4444;}
 else if(cmd==MAPLESU_REQUEST_SESSION_CLOSE){error=maplesu_request_owner_close();}
 else if(cmd==MAPLESU_REQUEST_ALLOC){uint64_t address=0;error=maplesu_request_owner_reserve_code(op.length,op.address,address);last.result=address;last.handle=address==gate?0x10:0x20;}
 else if(cmd==MAPLESU_REQUEST_WRITE){uint64_t address=op.handle==0x10?gate:payload;error=write_kernel_mem(address+op.address,reinterpret_cast<const void*>(op.data_user),op.length,KernMemProt::KMP_RW);reply(cmd,true,MAPLESU_EFFECT_WRITTEN);last.result=op.length;}
 else if(cmd==MAPLESU_REQUEST_RELEASE){error=free_kernel_mem(op.handle==0x10?gate:payload);}
 else if(cmd==MAPLESU_REQUEST_PUBLICATION_PREPARE){
  const auto& p=*reinterpret_cast<const maplesu_publication_prepare_v3*>(op.data_user);
  const auto* wire=reinterpret_cast<const maplesu_publication_member_v3*>(p.members_user);
  std::vector<RequestPublicationMember> members;for(uint32_t n=0;n<p.member_count;++n){
   RequestPublicationMember member{};member.role=wire[n].role;member.address=wire[n].handle==0x10?gate:payload;
   if(member.role==MAPLESU_PUBLICATION_SHARED_BOOL){member.address=wire[n].address;member.shared_bool=dependencies[n-2];}
   members.push_back(member);
  }
  RequestPublication pub{};error=maplesu_request_owner_publication_prepare(members.data(),p.member_count,p.gate_index,p.entry_address,p.gate_entry_offset,p.expected_instruction,p.payload_entry,pub);
  last.result=id;last.handle=control;
 }
 else if(cmd==MAPLESU_REQUEST_PUBLICATION_PUBLISH){RequestPublication p{id,control,{}};error=maplesu_request_owner_publication_publish(p);last.handle=id;}
 else if(cmd==MAPLESU_REQUEST_PUBLICATION_DISABLE){RequestPublication p{id,control,{}};error=maplesu_request_owner_publication_disable(p);last.handle=id;last.effects=MAPLESU_EFFECT_PUBLICATION_RETAINED|MAPLESU_EFFECT_GATE_PINNED;}
 else if(cmd==MAPLESU_REQUEST_PUBLICATION_RETIRE){RequestPublication p{id,control,{}};error=maplesu_request_owner_publication_retire(p);last.handle=id;}
 else assert(false);
 if(cmd==MAPLESU_REQUEST_PUBLICATION_PREPARE&&error==KModErr::OK){maplesu_publication_control_v3 c{};maplesu_request_owner_publication_control(RequestPublication{id,control,{}},c);reply(cmd,true,MAPLESU_EFFECT_PUBLICATION_PREPARED);last.result=id;last.handle=control;}
 struct Exchange {maplesu_response_owner_v3 response;maplesu_host_instance host;};
 Exchange exchange{};auto& response=exchange.response;response.completion_magic=MAPLESU_RESPONSE_MAGIC;response.version=MAPLESU_OWNER_PROTOCOL_VERSION;
 response.length=sizeof(response);response.command=cmd;response.status=last.status;response.result=last.result;response.metadata_address=last.handle;response.metadata_length=last.effects;
 exchange.host={0x88776655,0x11223344};
 if(!last.completed){result=uint64_t(int64_t(-EFAULT));return KModErr::OK;}
 std::memcpy(reinterpret_cast<void*>(destination),&exchange,sizeof(exchange));result=0;
 std::cout<<"exchange "<<std::hex<<cmd<<" "<<destination<<" ";for(uint8_t b:code)std::cout<<std::setw(2)<<std::setfill('0')<<unsigned(b);std::cout<<"\n";
 std::cout<<"response ";for(uint8_t b:std::vector<uint8_t>((uint8_t*)&exchange,(uint8_t*)&exchange+sizeof(exchange)))std::cout<<std::setw(2)<<std::setfill('0')<<unsigned(b);std::cout<<"\n";
 return error;
}
}

namespace maplesu::storage {
KModErr current_kernel_directory(std::string&) { assert(false); return KModErr::ERR_MODULE_ASM; }
}
namespace kernel_module {
KModErr request_kernel_alloc_mem(uint32_t,uint64_t&) { assert(false); return KModErr::ERR_MODULE_ASM; }
}
