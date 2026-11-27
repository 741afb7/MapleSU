#include "maplesu/compat/native_module_abi.h"
#include "maplesu_storage_worker_private.h"
#include "maplesu_request_identity.h"
#include "module_base_disk_storage.h"
#include "src/maplesu/module/operations.h"
#include "src/maplesu/compat/module_metadata_abi.h"
#include "src/maplesu/compat/storage_legacy.h"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <unistd.h>

extern "C" void __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(const char*);
extern "C" void __set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(const char*);
namespace fs=std::filesystem;
namespace {
thread_local uint64_t auth=1, generation=1;
pid_t session_process=0;
uint64_t session_generation=0;
unsigned rooted,deployments,closed,opened,cases,failures;
KModErr root_error=KModErr::OK;
bool fail_deployment=false;
int switch_after_auth_reads=0;
void check(bool value,const char* name){++cases;if(!value){++failures;std::printf("FAIL %s\n",name);}}
const std::string key="0123456789abcdefTEST_STORAGE_CREDENTIAL_FIXTURE_01";
const std::string directory="/data/0123456789abcdef0/";
void select(kernel_module::RequestAuthMode mode){(void)kernel_module::maplesu_request_set_auth_mode(mode);}
std::vector<uint8_t> metadata(const std::string& id,bool webui=false){
 using namespace maplesu::compat::native_module;
 std::string bytes=std::string(kNameBegin)+"fixture"+kNameEnd+kIdBegin+id+kIdEnd+
  kVersionBegin+"1"+kVersionEnd+kMinimumSdkBegin+"4.0.5"+kMinimumSdkEnd;
 if(webui)bytes+=std::string(kWebUiBegin)+"1"+kWebUiEnd;
 return {bytes.begin(),bytes.end()};
}
}
namespace default_modules::module_root {
KModErr set_current_root(bool manager){
 ++rooted;if(manager==kernel_module::maplesu_request_helper_mode())std::abort();
 if(is_failed(root_error))return root_error;
 if(kernel_module::maplesu_request_auth_authenticated(auth)){session_process=getpid();++session_generation;}
 return KModErr::OK;
}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state(){
 if(switch_after_auth_reads>0&&!--switch_after_auth_reads)auth=(++generation<<2)|uint64_t(RequestAuthMode::ManagerOwner);
 return auth;
}
KModErr maplesu_request_set_auth_mode(RequestAuthMode mode){auth=(++generation<<2)|uint64_t(mode);session_process=0;return KModErr::OK;}
bool maplesu_request_owner_lifetime_capture(RequestOwnerLifetimeToken& token){
 if(session_process!=getpid()||!maplesu_request_auth_authenticated(auth))return false;
 token.binding_.auth_state=auth;token.binding_.process=getpid();token.binding_.actual_uid=getuid();
 token.session_=session_generation;return true;
}
bool maplesu_request_owner_binding_matches(const RequestOwnerBinding& b){return b.auth_state==auth&&b.process==uint64_t(getpid())&&b.actual_uid==getuid()&&session_process==getpid();}
bool maplesu_request_owner_lifetime_matches(const RequestOwnerLifetimeToken& token){return token.session_==session_generation&&maplesu_request_owner_binding_matches(token.binding_);}
KModErr maplesu_request_owner_close(){++closed;session_process=0;return KModErr::OK;}
KModErr maplesu_request_helper_open(){++opened;session_process=getpid();++session_generation;return KModErr::OK;}
KModErr maplesu_request_owner_deployment(RequestDeploymentNamespace& out){
 ++deployments;if(fail_deployment||!maplesu_request_auth_authenticated(auth))return KModErr::ERR_MODULE_TEST_CHANNEL;
 std::memcpy(out.record.name,"fedcba9876543210",16);out.binding.auth_state=auth;out.binding.process=getpid();out.binding.actual_uid=getuid();return KModErr::OK;
}
CleanupReceiptScope::CleanupReceiptScope()noexcept:saved_errno_(errno),saved_process_(getpid()),saved_reply_{}{}
CleanupReceiptScope::~CleanupReceiptScope()noexcept{errno=saved_errno_;}
}
extern "C" int maplesu_private_chmod(const char*,unsigned){return 0;}

int main(){
 using namespace maplesu::storage;using kernel_module::RequestAuthMode;
 fs::create_directories(directory+"modules/legacy module/webroot");
 fs::create_directories(directory+"storage/legacy module");
 const int32_t stored=71;std::ofstream value(directory+"storage/legacy module/value",std::ios::binary);value.write(reinterpret_cast<const char*>(&stored),sizeof(stored));value.close();
 check(is_ok(kernel_module::maplesu_request_select_legacy(key.c_str())),"explicit legacy selection");
 __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c("legacy module");
 const auto before_deployments=deployments,before_closed=closed;
 {
 WorkerScope worker("ignored legacy host selector");
 check(is_ok(worker.root()),"legacy root does not require owner namespace");
 check(rooted==1&&deployments==before_deployments,"legacy roots exactly once with zero owner deployment");
 check(is_ok(worker.select_module("legacy module")),"legacy select keeps historical module identity");
 StorageContext context;
 check(is_ok(current_context(context))&&context.module_id=="legacy module","legacy context uses real module identity getter");
 check(std::string(context.locator.kernel_component)=="0123456789abcdef","legacy kernel directory prefix");
 check(std::string(context.locator.system_component)=="0123456789abcdef0","legacy system directory prefix plus first byte");
 std::string path;
 check(is_ok(current_system_directory(path))&&path==directory,"legacy real system directory");
 check(is_ok(current_kernel_directory(path))&&path=="/data/0123456789abcdef/","legacy real kernel directory");
 int32_t actual=0;
 check(is_ok(kernel_module::read_int32_disk_storage("value",actual))&&actual==stored,"legacy reads existing actual disk storage");
 check(kernel_module::read_int32_disk_storage("missing",actual)==KModErr::ERR_MODULE_STORAGE_NOT_FOUND,"legacy missing storage remains missing");
 maplesu::module::Descriptor parsed;
 check(is_ok(maplesu::module::parse_metadata(metadata("legacy module"),parsed))&&parsed.web_ui,"legacy untagged metadata detects existing webroot");
 parsed={};
 check(is_ok(maplesu::module::parse_metadata(metadata("missing module"),parsed))&&!parsed.web_ui,"legacy absent webroot remains absent");
 check(is_ok(worker.close_checked())&&closed==before_closed,"legacy close never closes owner session");
 }
 {
 root_error=KModErr::ERR_MODULE_ROOT_KEY;const auto before=rooted;WorkerScope worker;
 check(worker.root()==root_error&&rooted==before+1&&deployments==before_deployments,"legacy root failure preserved without namespace fallback");root_error=KModErr::OK;
 }
 kernel_module::maplesu_request_clear_legacy();StorageContext empty;empty.module_id="stale";
 check(current_context(empty)==KModErr::ERR_MODULE_ROOT_KEY&&empty.module_id.empty(),"missing real legacy credential rejects and clears output");
 maplesu::module::Descriptor tagged;
 check(is_ok(maplesu::module::parse_metadata(metadata("tagged module",true),tagged))&&tagged.web_ui,"explicit WebUI tag keeps metadata-only behavior");
 check(is_ok(kernel_module::maplesu_request_select_legacy(key.c_str())),"legacy credential restored explicitly");
 for(auto mode:{RequestAuthMode::ManagerOwner,RequestAuthMode::EarlyHelper}){
 select(mode);StorageContext out;
 check(maplesu::compat::current_legacy_storage_context(out)==KModErr::ERR_MODULE_PARAM,"legacy adapter rejects owner and helper mode");
 check(current_context(out)==KModErr::ERR_MODULE_PARAM,"authenticated mode never borrows legacy key without scope");
 const auto before=deployments;
 WorkerScope worker("owner module");check(is_ok(worker.root()),"authenticated worker root opens live scoped namespace");
 check(deployments==before+1,"authenticated worker reads own deployment");
 check(is_ok(current_context(out))&&out.module_id=="owner module"&&std::string(out.locator.kernel_component)=="fedcba9876543210","authenticated context never uses retained legacy prefix");
 check(is_ok(worker.select_module("selected module"))&&is_ok(current_context(out))&&out.module_id=="selected module","authenticated module selection unchanged");
 check(worker.select_module("../bad")==KModErr::ERR_MODULE_PARAM,"authenticated module path contract unchanged");
 ++session_generation;
 check(current_context(out)==KModErr::ERR_MODULE_ROOT_KEY,"closed or replaced session rejects stale scope");
 check(is_ok(worker.close_checked()),"authenticated session cleanup preserved");
 }
 select(RequestAuthMode::ManagerOwner);fail_deployment=true;
 {WorkerScope worker;check(worker.root()==KModErr::ERR_MODULE_TEST_CHANNEL,"owner deployment failure never falls back to legacy");}fail_deployment=false;
 for(uint64_t invalid:{UINT64_C(0),UINT64_C(2),UINT64_MAX}){auth=invalid;StorageContext out;WorkerScope worker;check(worker.root()==KModErr::ERR_MODULE_ROOT_KEY&&current_context(out)==KModErr::ERR_MODULE_ROOT_KEY,"invalid mode never enters legacy storage");}
 check(is_ok(kernel_module::maplesu_request_select_legacy(key.c_str())),"legacy reselected for identity transition");
 {WorkerScope worker;select(RequestAuthMode::ManagerOwner);check(worker.root()==KModErr::ERR_MODULE_ROOT_KEY&&worker.select_module("legacy module")==KModErr::ERR_MODULE_ROOT_KEY,"identity-changed worker does not reuse legacy scope");}
 check(is_ok(kernel_module::maplesu_request_select_legacy(key.c_str())),"legacy reselected before in-flight mode change");
 {StorageContext out;out.module_id="preserved";switch_after_auth_reads=2;
 check(maplesu::compat::current_legacy_storage_context(out)==KModErr::ERR_MODULE_ROOT_KEY&&out.module_id=="preserved","legacy adapter discards identity switched during capture");}
 fs::remove_all(directory);
 std::printf("LEGACY_STORAGE_CASES %u FAILURES %u\n",cases,failures);return failures!=0;
}
