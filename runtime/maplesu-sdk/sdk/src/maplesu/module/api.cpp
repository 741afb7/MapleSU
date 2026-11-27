#include "../compat/module_api_legacy_abi.h"
#include "../compat/module_carrier_conversion.h"
#include "operations.h"
#include "maplesu_module_install_private.h"

#include <cstring>
#include <string>
#include <vector>

namespace maplesu::module {
namespace {

KModErr parse_module_desc_from_zip_file(const char* module_zip_path,
                                        Descriptor& out_desc) {
    return maplesu::module::safe_parse_module_desc_from_zip_file(module_zip_path, out_desc);
}

template <typename Callback>
KModErr get_all_modules_with_cb(Callback cb) {

    std::vector<Record> list;
    KModErr err = maplesu::module::safe_get_all_modules(list);
    if (err != KModErr::OK) {
        return err;
    }
    for (const Record& record : list) {
        cb(record);
    }
    return KModErr::OK;
}

KModErr install_module_with_cb(const char* module_zip_path,
                               void (*cb)(const char* reason)) {

    std::string out_reason;
    KModErr err = maplesu::module_install::safe_install_module(module_zip_path, /*dev_run_once=*/false, out_reason);
    if (err == KModErr::ERR_MODULE_REJECT_INSTALL) {
        cb(out_reason.c_str());
    }
    return err;
}

KModErr install_module_dev_run_once_with_cb(const char* module_zip_path,
                                            void (*cb)(const char* reason)) {

    std::string out_reason;
    KModErr err = maplesu::module_install::safe_install_module(module_zip_path, /*dev_run_once=*/true, out_reason);
    if (err == KModErr::ERR_MODULE_REJECT_INSTALL) {
        cb(out_reason.c_str());
    }
    return err;
}

KModErr uninstall_module(const char* mod_id32) {
    return maplesu::module_install::safe_uninstall_module(mod_id32);
}

}  // namespace
}  // namespace maplesu::module

namespace maplesu::module_install {
KModErr install_with_completion(const char* archive, bool run_once,
                               std::string& reason, InstallCompletion& completion) {
    completion = {};
    reason.clear();
    return safe_install_module(archive, run_once, reason, completion);
}
}  // namespace maplesu::module_install

namespace maplesu::sdk {
KModErr parse_module_desc_from_zip_file(const char* module_zip_path,
                                        module_desc& out_desc) {
    maplesu::module::Descriptor parsed;
    const KModErr err = maplesu::module::parse_module_desc_from_zip_file(module_zip_path, parsed);
    if (err == KModErr::OK) maplesu::compat::copy_module_descriptor(out_desc, parsed);
    return err;
}
KModErr get_all_modules_with_cb(void (*cb)(const module_record*)) {
    return maplesu::module::get_all_modules_with_cb(
        [cb](const maplesu::module::Record& record) {
            module_record converted;
            maplesu::compat::copy_module_record(converted, record);
            cb(&converted);
        });
}
KModErr install_module_with_cb(const char* module_zip_path,
                               void (*cb)(const char* reason)) {
    return maplesu::module::install_module_with_cb(module_zip_path, cb);
}
KModErr install_module_dev_run_once_with_cb(const char* module_zip_path,
                                            void (*cb)(const char* reason)) {
    return maplesu::module::install_module_dev_run_once_with_cb(module_zip_path, cb);
}
KModErr uninstall_module(const char* mod_id32) {
    return maplesu::module::uninstall_module(mod_id32);
}
}  // namespace maplesu::sdk

// Key-bearing frozen API exports are intentionally absent in this SDK generation.
