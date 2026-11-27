// The runner supplies the exact 3f14fa9 public ABI header for the legacy build.
#ifdef FIXTURE_LEGACY
#include "baseline_native_module_abi.h"
#else
#include "maplesu/compat/native_module_abi.h"
#endif
#include <cassert>
#include <cstring>
#include <string>

MAPLESU_MODULE_DECLARE_EXPORTS(long long, unsigned int)

namespace {
int calls;
long long reply;
void directory(const char* value) {
#ifdef FIXTURE_LOADER_FLOW
    assert(value && std::strstr(value, "/modules/flow_module/"));
#else
    assert(std::strcmp(value, "/fixture/private/") == 0);
#endif
}
#ifdef FIXTURE_LEGACY
void credential(const char* value) {
    assert(value && std::strlen(value) == 48);
    for (int i = 0; i < 48; ++i) assert(value[i] == 'K');
}
#endif
}

#ifdef FIXTURE_LEGACY
extern "C" int fixture_legacy_calls() { return calls; }
extern "C" void fixture_legacy_reply(long long value) { reply = value; }
int MAPLESU_MODULE_ENTRY_NAME(const char* key, const char* dir) {
    credential(key); directory(dir); ++calls; return 201;
}
void MAPLESU_MODULE_INSTALL_NAME(const char* key, const char* dir, void (*reason)(const char*)) {
    credential(key); directory(dir); ++calls; reason("legacy rejection");
}
void MAPLESU_MODULE_UNINSTALL_NAME(const char* key, const char* dir) {
    credential(key); directory(dir); ++calls;
}
void MAPLESU_MODULE_WEBUI_NAME(const char* key, const char* dir, const char* root,
                              const char* id, int port, long long& error) {
    credential(key);
#else
extern "C" int fixture_keyless_calls() { return calls; }
extern "C" void fixture_keyless_reply(long long value) { reply = value; }
int MAPLESU_MODULE_ENTRY_NAME(const char* dir) { directory(dir); ++calls; return 101; }
void MAPLESU_MODULE_INSTALL_NAME(const char* dir, void (*reason)(const char*)) {
    directory(dir); ++calls; reason("keyless rejection");
}
void MAPLESU_MODULE_UNINSTALL_NAME(const char* dir) { directory(dir); ++calls; }
void MAPLESU_MODULE_WEBUI_NAME(const char* dir, const char* root,
                              const char* id, int port, long long& error) {
#endif
    directory(dir);
#ifdef FIXTURE_LOADER_FLOW
    assert(std::string(dir) + "webroot" == root);
    assert(std::strcmp(id, "flow_module") == 0 && port == 23456);
#else
    assert(std::strcmp(root, "/fixture/private/webroot") == 0);
    assert(std::strcmp(id, "module name.中文\\part") == 0 && port == 23456);
#endif
    ++calls;
    error = reply;
}

// The unchanged port/policy ABI is provided by only one object in the dual build.
#ifndef FIXTURE_OMIT_SHARED_EXPORTS
void MAPLESU_MODULE_LAST_SERVER_PORT_NAME(int& port, long long& error) {
    port = 34567; error = reply;
}
void MAPLESU_MODULE_BEFORE_SERVER_EXIT_NAME(unsigned int& action, long long& error) {
    action =
#ifdef FIXTURE_LOADER_FLOW
        0;
#else
        1;
#endif
    error = reply;
}
#endif
