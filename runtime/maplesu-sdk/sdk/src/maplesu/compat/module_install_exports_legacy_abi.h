#pragma once

// Frozen Android libc++ exports share the single MapleSU implementation.
#if defined(__ANDROID__)
namespace maplesu::compat::legacy_module_install {
extern decltype(sdk::safe_install_module) safe_install_module
    asm("_ZN10skroot_env19safe_install_moduleEPKcbRNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE")
    __attribute__((alias("_ZN7maplesu3sdk19safe_install_moduleEPKcbRNSt6__ndk112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEE")));
extern decltype(sdk::safe_uninstall_module) safe_uninstall_module
    asm("_ZN10skroot_env21safe_uninstall_moduleEPKc")
    __attribute__((alias("_ZN7maplesu3sdk21safe_uninstall_moduleEPKc")));
extern decltype(sdk::unsafe_uninstall_module) unsafe_uninstall_module
    asm("_ZN10skroot_env23unsafe_uninstall_moduleEPKcb")
    __attribute__((alias("_ZN7maplesu3sdk23unsafe_uninstall_moduleEPKcb")));
}

#endif
