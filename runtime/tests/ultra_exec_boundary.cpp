#include "maplesu_parasite.h"
#include "ultra_admission.h"
#include "path_injection.h"
#include <cstdio>
#include <cstdlib>
#include <unistd.h>

// Only SDK operations and namespace effects are replaced. Stdin, fork, exec,
// control sockets, process groups and wait/cancellation execute production code.
int maplesu_ultra_acquire_root() {
    return std::getenv("FIXTURE_DENY_ADMISSION") ? -20000 : 0;
}

namespace kernel_root {
ParasiteError parasite_precheck_app_with_cb(const char*, void (*callback)(const char*, AppDynlibStatus)) {
    if (std::getenv("FIXTURE_DENY_OPERATION")) return PARASITE_PROCESS;
    callback("/fixture/app/lib.so", Running);
    return PARASITE_OK;
}
ParasiteError parasite_implant_app(const char*, const char*) {
    return std::getenv("FIXTURE_DENY_OPERATION") ? PARASITE_IO : PARASITE_OK;
}
ParasiteError parasite_implant_su_env(const char*, const char*, const char*) {
    return std::getenv("FIXTURE_DENY_OPERATION") ? PARASITE_IO : PARASITE_OK;
}
}

int maplesu_inject_path(const char* package, uid_t uid, const char* directory, int wait_ms) {
    std::printf("INJECT_ARGS:%s:%u:%s:%d\n", package, uid, directory, wait_ms);
    return 0;
}

extern "C" int __wrap_setns(int, int) {
    if (std::getenv("FIXTURE_FORBID_SETNS")) _exit(89);
    std::fputs("FIXTURE_SETNS\n", stderr);
    return 0;
}
