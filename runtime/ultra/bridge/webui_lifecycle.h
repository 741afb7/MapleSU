#pragma once
#include "module_err_def.h"
#include <string>

namespace maplesu {
KModErr start_detached_module_webui(const char* id, int& port);
KModErr start_detached_module_webui(const char* id, int& port,
    std::string& module_directory);
}
