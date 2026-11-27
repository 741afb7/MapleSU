#include "../maplesu-sdk/sdk/src/maplesu/compat/module_runtime_legacy_abi.h"
#include "../maplesu-sdk/sdk/src/payload/webui_loader/src/module_host.h"
#include <cassert>
#include <cstdarg>
#include <cstdio>
#include <dlfcn.h>
#include <string>

namespace maplesu::payload {
void err_printf(const char*, ...) {}
}
namespace {
unsigned checks;
void check(bool condition, const char* name) {
    ++checks;
    std::printf("%s %s\n", condition ? "PASS" : "FAIL", name);
    assert(condition);
}
std::string reason;
void install_reason(const char* value) { reason = value; }
constexpr const char* directory = "/fixture/private/";
constexpr const char* webroot = "/fixture/private/webroot";
constexpr const char* id = "module name.中文\\part";
const std::string key(48, 'K');
}

int main() {
    namespace abi = maplesu::compat::native_module;
    using namespace maplesu::compat;
    for (const char* image : {"/work/keyless.so", "/work/legacy.so", "/work/dual.so"}) {
        const bool legacy = std::string(image) == "/work/legacy.so";
        void* library = dlopen(image, RTLD_NOW | RTLD_LOCAL);
        assert(library);
        const auto lookup = [library](const char* symbol) { return dlsym(library, symbol); };
        const auto entry = abi::resolve_export(lookup, abi::kModuleEntrySymbol, abi::kLegacyModuleEntrySymbol);
        const auto install = abi::resolve_export(lookup, abi::kModuleInstallSymbol, abi::kLegacyModuleInstallSymbol);
        const auto uninstall = abi::resolve_export(lookup, abi::kModuleUninstallSymbol, abi::kLegacyModuleUninstallSymbol);
        check(entry.abi == (legacy ? abi::Abi::LegacyRootKey : abi::Abi::KeylessV1), "explicit ABI selection and keyless priority");
        int result = -1;
        if (legacy) {
            check(invoke_module_entry(entry, nullptr, directory, result) == KModErr::ERR_MODULE_ROOT_KEY && result == -1,
                  "legacy entry rejects missing credential before execution");
            check(invoke_module_entry(entry, "short", directory, result) == KModErr::ERR_MODULE_ROOT_KEY,
                  "legacy entry rejects malformed credential");
            check(invoke_module_install(install, nullptr, directory, install_reason) == KModErr::ERR_MODULE_ROOT_KEY && reason.empty(),
                  "legacy install rejects absent credential");
            check(invoke_module_uninstall(uninstall, nullptr, directory) == KModErr::ERR_MODULE_ROOT_KEY,
                  "legacy uninstall rejects absent credential");
        }
        const char* credential = legacy ? key.c_str() : nullptr;
        check(invoke_module_entry(entry, credential, directory, result) == KModErr::OK && result == (legacy ? 201 : 101),
              "entry receives exact arguments and propagates return value");
        reason.clear();
        check(invoke_module_install(install, credential, directory, install_reason) == KModErr::OK &&
              reason == (legacy ? "legacy rejection" : "keyless rejection"), "void install ABI preserves callback reason");
        check(invoke_module_uninstall(uninstall, credential, directory) == KModErr::OK, "uninstall receives exact arguments");
        reason.clear();
        {
            maplesu::payload::webui::ModuleHost host;
            check(host.load(image) == 0 && host.loaded(), "actual ModuleHost dlopen resolves WebUI ABI");
            if (legacy) {
                check(host.start_web_ui(directory, webroot, id, 23456) == static_cast<long long>(KModErr::ERR_MODULE_ROOT_KEY),
                      "legacy WebUI rejects empty credential");
                check(host.start_web_ui(directory, webroot, id, 23456, "short") == static_cast<long long>(KModErr::ERR_MODULE_ROOT_KEY),
                      "legacy WebUI rejects malformed credential");
            }
            check(host.start_web_ui(directory, webroot, id, 23456, credential) == 0,
                  "actual WebUI receives credential and all parameters");
            int port = -1;
            unsigned int action = 0;
            check(host.last_server_port(port) == 0 && port == 34567, "unchanged published port ABI");
            check(host.before_server_exit_action(action) == 0 && action == 1, "unchanged exit policy ABI");
            const auto set_reply = reinterpret_cast<void (*)(long long)>(lookup(legacy ? "fixture_legacy_reply" : "fixture_keyless_reply"));
            set_reply(-20123);
            check(host.start_web_ui(directory, webroot, id, 23456, credential) == -20123,
                  "WebUI first module error preserved");
            port = -1;
            action = 0;
            check(host.last_server_port(port) == -20123 && port == -1, "failed port leaves caller output untouched");
            check(host.before_server_exit_action(action) == -20123 && action == 0, "failed policy leaves caller output untouched");
            set_reply(0);
            if (!legacy) {
                const auto legacy_calls = reinterpret_cast<int (*)()>(lookup("fixture_legacy_calls"));
                check(!legacy_calls || legacy_calls() == 0, "dual exports never invoke old ABI when keyless exists");
            }
        }
        dlclose(library);
    }
    {
        maplesu::payload::webui::ModuleHost host;
        check(host.load("/work/missing.so") == static_cast<long long>(KModErr::ERR_MODULE_DLSYM) && !host.loaded(),
              "missing WebUI export closes dlopen handle");
        check(host.start_web_ui(directory, webroot, id, 23456) == static_cast<long long>(KModErr::ERR_MODULE_DLSYM),
              "failed lookup leaves no callable export");
    }
    {
        maplesu::payload::webui::ModuleHost host;
        check(host.load("/work/absent.so") == static_cast<long long>(KModErr::ERR_MODULE_DLOPEN) && !host.loaded(),
              "missing image remains a real dlopen error");
    }
    int result = -1;
    check(invoke_module_entry(abi::Export{}, nullptr, directory, result) == KModErr::ERR_MODULE_PARAM && result == -1,
          "missing entry leaves result untouched");
    std::printf("checks=%u\n", checks);
}
