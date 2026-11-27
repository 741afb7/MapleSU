#include "../maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp"
#include "../ultra/bridge/installed_helper_readback.h"
#include "../ultra/bridge/manager_owner_root_probe.h"
#include "../maplesu-sdk/sdk/include/maplesu_root_private.h"
#include "../maplesu-sdk/sdk/include/maplesu_caps_private.h"
#include <thread>
#include <cstdio>
static pid_t parent;
static thread_local int child_registrations;
static bool deny_admission;
static unsigned pipe_calls, fork_calls;
extern "C" int __real_pipe2(int*, int);
extern "C" int __wrap_pipe2(int* fds, int flags) { ++pipe_calls; return __real_pipe2(fds, flags); }
extern "C" pid_t __real_fork();
extern "C" pid_t __wrap_fork() { ++fork_calls; return __real_fork(); }
extern "C" KModErr __wrap__ZN13kernel_module32maplesu_request_worker_admissionEv() {
 if(kernel_module::maplesu_request_auth_mode(kernel_module::maplesu_request_auth_state())!=kernel_module::RequestAuthMode::ManagerOwner)return KModErr::ERR_MODULE_PARAM;
 return deny_admission?KModErr::ERR_MODULE_ROOT_KEY:KModErr::OK;
}
extern "C" int __real___cxa_thread_atexit(void (*)(void*),void*,void*);
extern "C" int __wrap___cxa_thread_atexit(void (*f)(void*),void*p,void*d){if(parent&&getpid()!=parent){++child_registrations;return 0;}return __real___cxa_thread_atexit(f,p,d);}
extern "C" KModErr __real__ZN13kernel_module29maplesu_request_set_auth_modeENS_15RequestAuthModeE(kernel_module::RequestAuthMode);
extern "C" KModErr __wrap__ZN13kernel_module29maplesu_request_set_auth_modeENS_15RequestAuthModeE(kernel_module::RequestAuthMode mode){
 const auto result=__real__ZN13kernel_module29maplesu_request_set_auth_modeENS_15RequestAuthModeE(mode);
 if(result!=KModErr::OK)return result;
 if(getpid()==parent)return result;
 return child_registrations?KModErr::ERR_MODULE_PARAM:KModErr::ERR_MODULE_TEST_CHANNEL;
}
extern "C" int64_t maplesu_prepare_kernel_request()noexcept{return 0;}
// This fixture tests real request TLS preparation, not layout/publication locks.
extern "C" void maplesu_hook_fork_prepare()noexcept{}
extern "C" void maplesu_hook_fork_complete()noexcept{}
extern "C" bool maplesu_btf_try_fork_prepare()noexcept{return true;}
extern "C" void maplesu_btf_fork_complete()noexcept{}
namespace kernel_module{uint32_t get_page_size(){return 4096;} KModErr get_current_caps_snapshot(caps_info&,bool&){return KModErr::ERR_MODULE_TEST_CHANNEL;}}
namespace default_modules::module_root{KModErr set_current_root(bool){return KModErr::ERR_MODULE_TEST_CHANNEL;}RootTransitionResult current_root_transition(){return {};}}
static bool consume(void*,const uint8_t*,size_t){return true;}
int main(){parent=getpid();deny_admission=true;
 auto denied_read=maplesu::read_installed_helper("/data/ABCDEFGHIJKLMNOP/init",consume,nullptr);
 auto denied_owner=maplesu::probe_manager_owner_root();
 if(denied_read.sdk_error!=(long long)KModErr::ERR_MODULE_ROOT_KEY||denied_owner.channel_error!=(long long)KModErr::ERR_MODULE_ROOT_KEY||
    denied_read.child_pid||denied_owner.child_pid||denied_read.root.attempted||denied_owner.root.attempted||pipe_calls||fork_calls)return 3;
 if(kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::EarlyHelper)!=KModErr::OK)return 4;
 denied_read=maplesu::read_installed_helper("/data/ABCDEFGHIJKLMNOP/init",consume,nullptr);
 denied_owner=maplesu::probe_manager_owner_root();
 if(denied_read.sdk_error!=(long long)KModErr::ERR_MODULE_ROOT_KEY||denied_owner.channel_error!=(long long)KModErr::ERR_MODULE_ROOT_KEY||
    kernel_module::maplesu_request_auth_mode(kernel_module::maplesu_request_auth_state())!=kernel_module::RequestAuthMode::EarlyHelper||pipe_calls||fork_calls)return 5;
 if(kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::ManagerOwner)!=KModErr::OK)return 6;
 deny_admission=false;puts("PASS denied Manager/Helper callers: zero pipe/fork/ROOT, parent mode restored");
 for(int thread=0;thread<6;++thread){int error=0;std::thread worker([&]{
 auto read=maplesu::read_installed_helper("/data/ABCDEFGHIJKLMNOP/init",consume,nullptr);
 printf("read checkpoint=%d mode=%lld timeout=%d complete=%d us=%llu\n",read.root_checkpoint_received,(long long)read.mode_error,read.timed_out,read.complete,(unsigned long long)read.elapsed_us);
 if(!read.root_checkpoint_received||read.mode_error!=(long long)KModErr::ERR_MODULE_TEST_CHANNEL||read.timed_out||read.complete||!read.receipt_complete)error=1;
 auto owner=maplesu::probe_manager_owner_root();
 printf("owner checkpoint=%d mode=%lld timeout=%d verified=%d us=%llu\n",owner.root_checkpoint_received,(long long)owner.mode_error,owner.timed_out,owner.root_verified,(unsigned long long)owner.elapsed_us);
 if(!owner.root_checkpoint_received||owner.mode_error!=(long long)KModErr::ERR_MODULE_TEST_CHANNEL||owner.timed_out||owner.root_verified||!owner.receipt_complete)error=2;
 });worker.join();if(error)return error;}return 0;}
