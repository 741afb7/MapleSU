// webui_loader -- module hosting implementation.
//
#include "module_host.h"

#include <dlfcn.h>

#include "module_err_def.h"
#include "payload_support.h"

namespace maplesu::payload::webui {
namespace {

constexpr long long as_code(KModErr err) { return static_cast<long long>(err); }

}  // namespace

ModuleHost::~ModuleHost() {
    if (handle_ != nullptr) {
        dlclose(handle_);
        handle_ = nullptr;
    }
}

long long ModuleHost::load(const std::string& elf_path) {
    handle_ = dlopen(elf_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle_ == nullptr) {
        const char* reason = dlerror();
        err_printf("[webui_loader] dlopen: %s, failed: %s\n", elf_path.c_str(),
                   reason != nullptr ? reason : "unknown loader error");
        return as_code(KModErr::ERR_MODULE_DLOPEN);
    }

    web_ui_main_ = maplesu::compat::native_module::resolve_export(
        [this](const char* symbol) { return dlsym(handle_, symbol); }, kWebUiMainSymbol,
        maplesu::compat::native_module::kLegacyWebUiMainSymbol);
    last_server_port_ =
        reinterpret_cast<LastServerPortFn>(dlsym(handle_, kLastServerPortSymbol));
    exit_action_ = reinterpret_cast<BeforeServerExitActionFn>(
        dlsym(handle_, kBeforeServerExitActionSymbol));
    if (!web_ui_main_ || last_server_port_ == nullptr ||
        exit_action_ == nullptr) {
        const char* reason = dlerror();
        err_printf("[webui_loader] dlsym failed: %s\n",
                   reason != nullptr ? reason : "required WebUI entry point missing");
        dlclose(handle_);
        handle_ = nullptr;
        web_ui_main_ = {};
        last_server_port_ = nullptr;
        exit_action_ = nullptr;
        return as_code(KModErr::ERR_MODULE_DLSYM);
    }
    return as_code(KModErr::OK);
}

long long ModuleHost::start_web_ui(const char* module_private_dir,
                                   const char* module_webroot_dir, const char* mod_id32,
                                   int port, const char* legacy_credential) {
    if (!web_ui_main_) return as_code(KModErr::ERR_MODULE_DLSYM);
    long long err = as_code(KModErr::OK);
    if (web_ui_main_.abi == maplesu::compat::native_module::Abi::LegacyRootKey) {
        if (!maplesu::compat::native_module::valid_legacy_credential(legacy_credential))
            return as_code(KModErr::ERR_MODULE_ROOT_KEY);
        reinterpret_cast<LegacyWebUiMainFn>(web_ui_main_.address)(legacy_credential, module_private_dir,
            module_webroot_dir, mod_id32, port, err);
    } else {
        reinterpret_cast<WebUiMainFn>(web_ui_main_.address)(module_private_dir, module_webroot_dir,
            mod_id32, port, err);
    }
    return err;
}

long long ModuleHost::last_server_port(int& out_port) {
    if (last_server_port_ == nullptr) return as_code(KModErr::ERR_MODULE_DLSYM);
    long long err = as_code(KModErr::OK);
    int port = 0;
    last_server_port_(port, err);
    if (err == as_code(KModErr::OK)) out_port = port;
    return err;
}

long long ModuleHost::before_server_exit_action(unsigned int& out_action) {
    if (exit_action_ == nullptr) return as_code(KModErr::ERR_MODULE_DLSYM);
    long long err = as_code(KModErr::OK);
    unsigned int action = 0;
    exit_action_(action, err);
    if (err == as_code(KModErr::OK)) out_action = action;
    return err;
}

}  // namespace maplesu::payload::webui
