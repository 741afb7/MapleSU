#pragma once

#include "module_err_def.h"
#include <string>

namespace maplesu::module_install {

struct InstallCompletion {
    bool committed = false;
    bool immediate_start_failed = false;
    bool cleanup_complete = false;
    bool cleanup_pending = false;
    bool needs_reboot = false;
};

__attribute__((visibility("hidden")))
KModErr install_with_completion(const char* archive, bool run_once,
                               std::string& reason, InstallCompletion& completion);

}  // namespace maplesu::module_install
