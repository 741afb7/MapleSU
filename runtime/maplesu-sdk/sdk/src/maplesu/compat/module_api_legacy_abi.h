#pragma once

// m058 consumes the legacy natural-layout ABI in its own TU.
#include "module_metadata_abi.h"


namespace maplesu::sdk {
KModErr safe_parse_module_desc_from_zip_file(const char* module_zip_path, module_desc& out_desc);
KModErr safe_get_all_modules(std::vector<module_record>& out_list);
KModErr safe_install_module(const char* module_zip_path, bool dev_run_once, std::string& out_reason);
KModErr safe_uninstall_module(const char* mod_id32);

KModErr parse_module_desc_from_zip_file(const char* module_zip_path, module_desc& out_desc);
KModErr get_all_modules_with_cb(void (*cb)(const module_record*));
KModErr install_module_with_cb(const char* module_zip_path, void (*cb)(const char* reason));
KModErr install_module_dev_run_once_with_cb(const char* module_zip_path, void (*cb)(const char* reason));
KModErr uninstall_module(const char* mod_id32);
}  // namespace maplesu::sdk
