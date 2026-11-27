#pragma once
#include "module_err_def.h"
#include <string>
#include <vector>
namespace maplesu {
// Fork while the signed app identity still exists; never run this from RootService.
KModErr capture_module_environment(std::vector<std::string>& out);
}
