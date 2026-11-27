// Frozen external pipe ABI used by the runtime log compatibility boundary.
#pragma once
#include <cstddef>
#include <string>
#include <unistd.h>
#include "module_err_def.h"

#include "fork_legacy_abi.h"

namespace skroot_env {
KModErr read_skroot_log_cb(void (*callback)(const char*));
KModErr set_skroot_log_enabled(bool enable);
bool is_skroot_log_enabled();
KModErr clear_skroot_log();
}  // namespace skroot_env
