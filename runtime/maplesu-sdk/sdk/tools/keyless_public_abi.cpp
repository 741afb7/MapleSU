// Linked against the complete current archive by build.py. Never executed.
#include "kernel_module_kit_umbrella.h"
#include "maplesu_module_control.h"
#include "maplesu_parasite.h"
#include "maplesu_sdk/module_callback.h"
#include <cstddef>
#include <cstdint>

#define SDK_API(name, ...) \
    using abi_type_##name = __VA_ARGS__; \
    __attribute__((used)) abi_type_##name abi_##name = &maplesu::sdk::name
using maplesu::sdk::module_desc;
using maplesu::sdk::module_record;
using maplesu::sdk::su_auth_item;
SDK_API(get_root, KModErr (*)());
SDK_API(run_root_cmd, KModErr (*)(const char*, std::string&));
SDK_API(install_maplesu_environment, KModErr (*)(maplesu::sdk::InstallMode, const maplesu::sdk::InstallConfig&));
SDK_API(uninstall_maplesu_environment, KModErr (*)());
SDK_API(get_maplesu_environment_state, maplesu::sdk::MapleSUEnvState (*)());
SDK_API(get_installed_maplesu_environment_version, KModErr (*)(maplesu::sdk::MapleSUSdkVersion&));
SDK_API(get_sdk_version, maplesu::sdk::MapleSUSdkVersion (*)());
SDK_API(get_all_modules_list, KModErr (*)(std::vector<module_record>&));
SDK_API(parse_module_desc_from_zip_file, KModErr (*)(const char*, module_desc&));
SDK_API(install_module, KModErr (*)(const char*, std::string&));
SDK_API(install_module_dev_run_once, KModErr (*)(const char*, std::string&));
SDK_API(uninstall_module, KModErr (*)(const char*));
SDK_API(get_su_auth_list, KModErr (*)(std::vector<su_auth_item>&));
SDK_API(add_su_auth_list, KModErr (*)(const char*));
SDK_API(remove_su_auth_list, KModErr (*)(const char*));
SDK_API(clear_su_auth_list, KModErr (*)());
SDK_API(read_maplesu_log, KModErr (*)(std::string&));
SDK_API(set_maplesu_log_enabled, KModErr (*)(bool));
SDK_API(is_maplesu_log_enabled, bool (*)());
SDK_API(clear_maplesu_log, KModErr (*)());
SDK_API(set_boot_fail_protect_enabled, KModErr (*)(bool));
SDK_API(is_boot_fail_protect_enabled, bool (*)());
SDK_API(set_adb_forced_disabled, KModErr (*)(bool));
SDK_API(is_adb_forced_disabled, bool (*)());
SDK_API(get_root_status_report, const char* (*)());
SDK_API(test_maplesu_basics, KModErr (*)(maplesu::sdk::BasicItem, std::string&));
SDK_API(test_maplesu_deafult_module, KModErr (*)(maplesu::sdk::DeafultModuleName, std::string&));
SDK_API(restart_zygote64, KModErr (*)());
SDK_API(read_maplesu_log_cb, KModErr (*)(void (*)(const char*)));
SDK_API(get_all_modules_with_cb, KModErr (*)(void (*)(const module_record*)));
SDK_API(install_module_with_cb, KModErr (*)(const char*, void (*)(const char*)));
SDK_API(install_module_dev_run_once_with_cb, KModErr (*)(const char*, void (*)(const char*)));
SDK_API(get_su_auth_list_with_cb, KModErr (*)(void (*)(const su_auth_item*)));
SDK_API(test_maplesu_basics_with_cb, KModErr (*)(maplesu::sdk::BasicItem, void (*)(const char*)));
SDK_API(test_maplesu_deafult_module_with_cb, KModErr (*)(maplesu::sdk::DeafultModuleName, void (*)(const char*)));
#undef SDK_API

#define ABI_API(name, target, ...) \
    using abi_type_##name = __VA_ARGS__; \
    __attribute__((used)) abi_type_##name abi_##name = &target
ABI_API(webui, maplesu::sdk::features::web_ui::start_module_web_ui_server_async,
        KModErr (*)(const char*, int&));
ABI_API(on_install, maplesu::sdk::features::callback::invoke_module_on_install,
        KModErr (*)(const std::string&, std::string&));
ABI_API(on_uninstall, maplesu::sdk::features::callback::invoke_module_on_uninstall,
        KModErr (*)(const std::string&));
ABI_API(module_control_capability, maplesu::module_control_capability, int64_t (*)(bool&));
ABI_API(get_module_control, maplesu::get_module_control, int64_t (*)(const char*, maplesu::ModuleControlState&));
ABI_API(get_module_controls, maplesu::get_module_controls,
        std::vector<maplesu::ModuleControlResult> (*)(const std::vector<module_record>&));
ABI_API(set_module_enabled, maplesu::set_module_enabled,
        int64_t (*)(const char*, bool, maplesu::ModuleControlState&));
ABI_API(parasite_precheck_app_with_cb, kernel_root::parasite_precheck_app_with_cb,
        ssize_t (*)(const char*, void (*)(const char*, kernel_root::AppDynlibStatus)));
ABI_API(parasite_implant_app, kernel_root::parasite_implant_app, ssize_t (*)(const char*, const char*));
ABI_API(parasite_implant_su_env, kernel_root::parasite_implant_su_env,
        ssize_t (*)(const char*, const char*, const char*));
#undef ABI_API

static_assert(sizeof(maplesu::sdk::MapleSUSdkVersion) == 12);
static_assert(alignof(maplesu::sdk::MapleSUSdkVersion) == 4);
static_assert(sizeof(module_desc) == 9232 && alignof(module_desc) == 4);
static_assert(offsetof(module_desc, min_sdk_ver) == 9220);
static_assert(sizeof(module_record) == 9236 && alignof(module_record) == 1);
static_assert(offsetof(module_record, state) == 9232);
static_assert(sizeof(su_auth_item) == 256);
static_assert(sizeof(maplesu::ModuleControlState) == 20);

int maplesu_module_main(const char*) { return 0; }
std::string module_on_install(const char*) { return {}; }
void module_on_uninstall(const char*) {}
class AbiWebUi final : public kernel_module::WebUIHttpHandler {};
MAPLESU_MODULE_NAME("MapleSU ABI probe")
MAPLESU_MODULE_VERSION("5.2.1")
MAPLESU_MODULE_DESC("Build input; never installed")
MAPLESU_MODULE_AUTHOR("MapleSU")
MAPLESU_MODULE_ID32("0123456789abcdef0123456789abcdef")
MAPLESU_MODULE_WEB_UI(AbiWebUi)
MAPLESU_MODULE_ON_INSTALL(module_on_install)
MAPLESU_MODULE_ON_UNINSTALL(module_on_uninstall)
