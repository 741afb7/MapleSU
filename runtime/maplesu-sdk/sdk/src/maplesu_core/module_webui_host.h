#pragma once
#include <string>
#include "module_err_def.h"

namespace maplesu::sdk::features::web_ui {
// Internal MapleSU host entry. The public legacy entry still opens the browser.
KModErr start_module_web_ui_server_for_manager(const char* mod_id32,
                                              int& out_port, std::string& out_module_dir);
}
