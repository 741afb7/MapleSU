// webui_loader -- host side of a MapleSU module's WebUI.
//
// The program the kit installs next to the module tree is deliberately thin: it
// loads the module ELF the kernel already runs (the same file, mapped again in
// this userland process), resolves the three WebUI entry points the module kit
// exports, and drives them.  The module keeps ownership of the HTTP handler; the
// loader owns the process lifetime, the launch URL and the exit policy.
//
#pragma once

#include "maplesu/compat/native_module_abi.h"
#include <string>

namespace maplesu::payload::webui {

// The three exported entry points every WebUI-capable module carries.  The
// suffixes are part of the module ABI, so they are spelled out verbatim.
inline constexpr const char* kWebUiMainSymbol =
    maplesu::compat::native_module::kWebUiMainSymbol;
inline constexpr const char* kLastServerPortSymbol =
    maplesu::compat::native_module::kLastServerPortSymbol;
inline constexpr const char* kBeforeServerExitActionSymbol =
    maplesu::compat::native_module::kBeforeServerExitActionSymbol;

// Signature of the module's WebUI bootstrap:
//   (module_private_dir, module_webroot_dir, mod_id32, port, err)
// It starts the module's CivetWeb server and reports the outcome in `err`.
using WebUiMainFn = void (*)(const char*, const char*, const char*,
                             int, long long&);
using LegacyWebUiMainFn = void (*)(const char*, const char*, const char*, const char*,
                                   int, long long&);
// Asks the module which port its server actually listens on.
using LastServerPortFn = void (*)(int&, long long&);
// Asks the module what the process should do once its owner disappears:
// 0 = exit, 1 = keep running.
using BeforeServerExitActionFn = void (*)(unsigned int&, long long&);

class ModuleHost {
public:
    ModuleHost() = default;
    ~ModuleHost();
    ModuleHost(const ModuleHost&) = delete;
    ModuleHost& operator=(const ModuleHost&) = delete;

    // Maps the module ELF and resolves the entry points.  Returns 0 on success,
    // otherwise the negative module error code the loader logs
    // (dlopen failure = -19948, dlsym failure = -19946).
    long long load(const std::string& elf_path);

    // Calls the module's WebUI bootstrap and returns its error code.
    long long start_web_ui(const char* module_private_dir,
                           const char* module_webroot_dir, const char* mod_id32,
                           int port, const char* legacy_credential = nullptr);

    // Reads back the port the module's server publishes.  Returns 0 when the
    // module answers, its negative error code otherwise.
    long long last_server_port(int& out_port);

    // Reads back the module's exit policy.  Returns 0 when the module answers.
    long long before_server_exit_action(unsigned int& out_action);

    bool loaded() const { return handle_ != nullptr; }

private:
    void* handle_ = nullptr;
    maplesu::compat::native_module::Export web_ui_main_;
    LastServerPortFn last_server_port_ = nullptr;
    BeforeServerExitActionFn exit_action_ = nullptr;
};

}  // namespace maplesu::payload::webui
