#include "ultra_admission.h"
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"

int maplesu_ultra_acquire_root() {
    return static_cast<int>(maplesu::sdk::get_root());
}
