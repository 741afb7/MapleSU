// Compatibility boundary used by the paired SDK backend in the helper process.
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

// packages is const std::vector<std::string>*. The maintained SDK transaction
// prepares/freezes copies, publishes the kernel allowlist and opens the result.
// Returns the original KModErr as int64_t, including failures and empty revokes.
int64_t maplesu_helper_su_write(const void* packages, const char* module);

// object is default_modules::module_su_redirect::PatchDoExecve*. control is the
// full 64-bit kernel control address. Calls patch_do_execve(control) and returns
// its KModErr; this operation does not replace the current userspace process.
int64_t maplesu_helper_su_exec(void* object, uint64_t control, const char* module);

#ifdef __cplusplus
}
#endif
