// Actual m061 workers, WorkerScope and fork/pipe transport. Root/auth/storage are boundary fixtures.
#include "maplesu_storage_worker_private.h"
#include "src/maplesu_core/closure_fork_pipe.h"
#include "src/maplesu/compat/su_auth_legacy_abi.h"
#include "src/module/su_redirect_tu/su_uid_profile_private.h"
#include <cerrno>
#include <cstdio>
#include <sys/mman.h>
#include <sys/wait.h>

namespace fixture {
struct Shared { unsigned roots, transactions, reads, closes, selects; };
Shared* shared;
thread_local uint64_t auth=1;
uint64_t expected=1;
pid_t parent, session=0;
KModErr root_error=KModErr::OK, body_error=KModErr::OK, close_error=KModErr::OK, admission_error=KModErr::OK;
unsigned callbacks, checks, failures;
KModErr body() {
 if(getpid()==parent || auth!=expected)return KModErr::ERR_MODULE_TEST_CHANNEL;
 return body_error;
}
void check(bool value,const char* message) {++checks;if(!value){++failures;std::printf("FAIL %s\n",message);}}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state(){return fixture::auth;}
KModErr maplesu_request_set_auth_mode(RequestAuthMode mode){++fixture::shared->selects;fixture::auth=((fixture::auth>>2)+1)*4+uint64_t(mode);return KModErr::OK;}
KModErr maplesu_request_worker_admission(){return maplesu_request_auth_usable(fixture::auth)?fixture::admission_error:KModErr::ERR_MODULE_ROOT_KEY;}
bool maplesu_request_owner_lifetime_capture(RequestOwnerLifetimeToken&){return fixture::session==getpid();}
KModErr maplesu_request_owner_close(){++fixture::shared->closes;fixture::session=0;return fixture::shared->closes>1?fixture::close_error:KModErr::OK;}
KModErr maplesu_request_helper_open(){fixture::session=getpid();return KModErr::OK;}
KModErr maplesu_request_owner_deployment(RequestDeploymentNamespace& out){out.binding.actual_uid=0;std::memcpy(out.record.name,"0123456789abcdef",16);return KModErr::OK;}
bool maplesu_request_owner_binding_matches(const RequestOwnerBinding&){return fixture::session==getpid();}
CleanupReceiptScope::CleanupReceiptScope()noexcept:saved_errno_(errno),saved_process_(getpid()),saved_reply_{}{}
CleanupReceiptScope::~CleanupReceiptScope()noexcept{errno=saved_errno_;}
}
namespace maplesu::storage {
StorageScope::StorageScope(const StorageLocator&,const char*){error_=KModErr::OK;}
StorageScope::~StorageScope()=default;
KModErr StorageScope::select_module(const char*){return KModErr::OK;}
}
namespace default_modules::module_root {
KModErr set_current_root(bool manager){
 ++fixture::shared->roots;
 if(manager==kernel_module::maplesu_request_helper_mode()||fixture::auth!=fixture::expected)return KModErr::ERR_MODULE_TEST_CHANNEL;
 if(is_failed(fixture::root_error))return fixture::root_error;
 if(kernel_module::maplesu_request_owner_mode())fixture::session=getpid();
 return KModErr::OK;
}
}
namespace default_modules::module_su_redirect {
KModErr get_su_allowlist(void(*callback)(const maplesu::sdk::su_auth_item*)){
 ++fixture::shared->reads;const auto e=fixture::body();if(is_failed(e))return e;
 maplesu::sdk::su_auth_item item{};std::strcpy(item.app_package_name,"fixture.package");callback(&item);return KModErr::OK;
}
}
namespace maplesu::su_auth::detail {
KModErr transact_add(const char*,ResultV1& result){++fixture::shared->transactions;return finish(result,fixture::body());}
KModErr transact_remove(const char*,ResultV1& result){++fixture::shared->transactions;return finish(result,fixture::body());}
KModErr transact_clear(ResultV1& result){++fixture::shared->transactions;return finish(result,fixture::body());}
KModErr transact_set_uid_profile(uint32_t uid,const char*,bool,ResultV1& result){++fixture::shared->transactions;if(uid!=1012345)return fail(result,KModErr::ERR_MODULE_PARAM,Phase::ActionValidate);return finish(result,fixture::body());}
KModErr transact_get_uid_profiles(void(*callback)(const UidProfileV1*)){
 ++fixture::shared->reads;const auto e=fixture::body();if(is_failed(e))return e;
 UidProfileV1 item{};item.uid=1012345;item.allow=1;std::strcpy(item.package,"fixture.package");callback(&item);return KModErr::OK;
}
}
#include "su-auth-production.inc"
static void run(uint64_t source,unsigned operation,unsigned fault){
 using namespace fixture;
 std::memset(shared,0,sizeof(*shared));auth=expected=source;session=0;callbacks=0;
 root_error=fault==1?KModErr::ERR_MODULE_OPEN_FILE:KModErr::OK;
 body_error=fault==2?KModErr::ERR_MODULE_READ_FILE:KModErr::OK;
 close_error=fault==3?KModErr::ERR_MODULE_WRITE_FILE:KModErr::OK;
 admission_error=fault==4?KModErr::ERR_MODULE_ROOT_KEY:KModErr::OK;
 maplesu::su_auth::ResultV1 receipt(maplesu::su_auth::Operation::Add);
 KModErr error;
 switch(operation){
 case 0:error=maplesu::sdk::get_su_auth_list_with_cb([](const maplesu::sdk::su_auth_item* item){check(std::string(item->app_package_name)=="fixture.package","record bytes preserved");++callbacks;});break;
 case 1:error=maplesu::su_auth::get_uid_profiles_with_cb([](const maplesu::su_auth::UidProfileV1* item){check(item->uid==1012345,"fullUID preserved");++callbacks;});break;
 case 2:error=maplesu::su_auth::add("fixture.package",receipt);break;
 case 3:error=maplesu::su_auth::remove("fixture.package",receipt);break;
 case 4:error=maplesu::su_auth::clear(receipt);break;
 default:error=maplesu::su_auth::set_uid_profile(1012345,"fixture.package",true,receipt);break;
 }
 KModErr want=KModErr::OK;
 if(fault==4||!kernel_module::maplesu_request_auth_usable(source))want=KModErr::ERR_MODULE_ROOT_KEY;
 else if(fault==1)want=root_error;else if(fault==2)want=body_error;
 else if(fault==3&&kernel_module::maplesu_request_auth_authenticated(source))want=close_error;
 check(error==want,"selected protocol and first error retained");
 check(auth==source,"parent protocol generation unchanged");
 check(shared->selects==0,"worker does not replace selected protocol");
 if(fault==4||!kernel_module::maplesu_request_auth_usable(source))check(shared->roots==0&&shared->transactions==0&&shared->reads==0,"admission failure never executes worker");
 else {check(shared->roots==1,"exactly one worker roots");if(fault==1)check(shared->transactions==0&&shared->reads==0,"failed root/session close never executes transaction");}
 if(operation<2)check(callbacks==(is_ok(want)?1u:0u),"failed read publishes zero callbacks");
 else if(fault!=4&&kernel_module::maplesu_request_auth_usable(source))check(receipt.childWaitStatusValid&&receipt.transportStatus==uint32_t(maplesu::su_auth::Transport::Complete),"real child transaction frame completed");
}
int main(){
 using namespace fixture;parent=getpid();shared=static_cast<Shared*>(mmap(nullptr,sizeof(Shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0));if(shared==MAP_FAILED)return 80;
 for(uint64_t mode:{UINT64_C(1),UINT64_C(4),UINT64_C(7)})for(unsigned op=0;op<6;++op)for(unsigned fault=0;fault<5;++fault)run(mode,op,fault);
 for(uint64_t mode:{UINT64_C(0),UINT64_C(2),UINT64_MAX})for(unsigned op=0;op<6;++op)run(mode,op,0);
 munmap(shared,sizeof(Shared));std::printf("SU worker protocol checks=%u failures=%u\n",checks,failures);return failures?1:0;
}
