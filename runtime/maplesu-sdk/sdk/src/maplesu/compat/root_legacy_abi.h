#pragma once

#include "module_err_def.h"
#include <string>

namespace skroot_env {
KModErr get_root();
KModErr run_root_cmd(const char* cmd, std::string& out_result);
}  // namespace skroot_env
