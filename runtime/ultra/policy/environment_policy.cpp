#include "kernel_policy_gate.h"
#include <cstdio>
#include <cstring>
#include <exception>
#include <unistd.h>

// Preserve synchronous environment setup without rerunning OEM initialization.

// An environment-owned synchronous entry, not a user module or an app probe.
// The bootstrap calls it before native SELinux setup. A preparation failure
// cancels hiding and preserves ordinary loading when the user allowed it;
// unrecoverable configuration errors still leave SDK setup and Identity intact.
extern "C" __attribute__((visibility("default")))
int maplesu_environment_policy_start() {
    if (getuid() != 0 || geteuid() != 0) return 1;
    try {
        auto store = maplesu::module_policy::Store::open(true);
        store.prepare_boot(maplesu::module_policy::current_boot_id(),
                           maplesu::module_policy::prepare_kernel_policy,
#ifdef MAPLESU_POLICY_HIDE_PAYLOAD
                           true
#else
                           false
#endif
        );
        return 0;
    } catch (const std::exception& error) {
        // Reasons contain fixed operation/symbol names, never caller secrets.
        fprintf(stderr, "MapleSU module policy preparation failed: %s\n", error.what());
        try {
            maplesu::module_policy::Store::open(false).record_compatibility(
                "schema=1 operation=boot-preparation boot=" + maplesu::module_policy::current_boot_id() +
                "\nresult=failed reason=" + error.what() + "\nEND\n");
        } catch (...) { }
        return 3;
    }
}
