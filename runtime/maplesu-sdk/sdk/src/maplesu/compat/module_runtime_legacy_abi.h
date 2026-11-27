#pragma once
#include "maplesu/compat/native_module_abi.h"
#include "module_err_def.h"

namespace maplesu::compat {
using native_module::kModuleEntrySymbol;
inline KModErr invoke_module_entry(void* function, const char* directory, int& result) {
    if (!function || !directory) return KModErr::ERR_MODULE_PARAM;
    using Entry = int (*)(const char*);
    result = reinterpret_cast<Entry>(function)(directory);
    return KModErr::OK;
}
inline KModErr invoke_module_entry(native_module::Export entry, const char* credential,
                                   const char* directory, int& result) {
    if (!entry || !directory) return KModErr::ERR_MODULE_PARAM;
    if (entry.abi == native_module::Abi::KeylessV1)
        return invoke_module_entry(entry.address, directory, result);
    if (!native_module::valid_legacy_credential(credential)) return KModErr::ERR_MODULE_ROOT_KEY;
    using LegacyEntry = int (*)(const char*, const char*);
    result = reinterpret_cast<LegacyEntry>(entry.address)(credential, directory);
    return KModErr::OK;
}

inline KModErr invoke_module_install(native_module::Export entry, const char* credential,
                                     const char* directory, void (*reason)(const char*)) {
    if (!entry || !directory || !reason) return KModErr::ERR_MODULE_PARAM;
    if (entry.abi == native_module::Abi::KeylessV1) {
        using Install = void (*)(const char*, void (*)(const char*));
        reinterpret_cast<Install>(entry.address)(directory, reason);
    } else {
        if (!native_module::valid_legacy_credential(credential)) return KModErr::ERR_MODULE_ROOT_KEY;
        using LegacyInstall = void (*)(const char*, const char*, void (*)(const char*));
        reinterpret_cast<LegacyInstall>(entry.address)(credential, directory, reason);
    }
    return KModErr::OK;
}

inline KModErr invoke_module_uninstall(native_module::Export entry, const char* credential,
                                       const char* directory) {
    if (!entry || !directory) return KModErr::ERR_MODULE_PARAM;
    if (entry.abi == native_module::Abi::KeylessV1) {
        using Uninstall = void (*)(const char*);
        reinterpret_cast<Uninstall>(entry.address)(directory);
    } else {
        if (!native_module::valid_legacy_credential(credential)) return KModErr::ERR_MODULE_ROOT_KEY;
        using LegacyUninstall = void (*)(const char*, const char*);
        reinterpret_cast<LegacyUninstall>(entry.address)(credential, directory);
    }
    return KModErr::OK;
}
}
