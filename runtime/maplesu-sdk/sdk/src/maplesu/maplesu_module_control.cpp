// maplesu module control implementation.
//
#include "../../../../ultra/modules/module_control.cpp"
#include "compat/module_control_exports_legacy_abi.h"

namespace maplesu::compat {
std::vector<ModuleControlResult> get_module_controls_legacy(
    const std::vector<sdk::module_record>& records) {
    return maplesu::get_module_controls( records);
}
} // namespace maplesu::compat
