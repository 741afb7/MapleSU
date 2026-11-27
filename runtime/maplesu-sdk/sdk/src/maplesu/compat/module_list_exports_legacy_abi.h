#pragma once

// Frozen Android libc++ exports share the single MapleSU implementation.
#if defined(__ANDROID__)
namespace maplesu::compat::legacy_module_list {
extern decltype(sdk::safe_get_all_modules) safe_get_all_modules
    asm("_ZN10skroot_env20safe_get_all_modulesERNSt6__ndk16vectorINS_13module_recordENS0_9allocatorIS2_EEEE")
    __attribute__((alias("_ZN7maplesu3sdk20safe_get_all_modulesERNSt6__ndk16vectorINS0_13module_recordENS1_9allocatorIS3_EEEE")));
extern decltype(sdk::unsafe_get_all_modules) unsafe_get_all_modules
    asm("_ZN10skroot_env22unsafe_get_all_modulesERNSt6__ndk16vectorINS_13module_recordENS0_9allocatorIS2_EEEE")
    __attribute__((alias("_ZN7maplesu3sdk22unsafe_get_all_modulesERNSt6__ndk16vectorINS0_13module_recordENS1_9allocatorIS3_EEEE")));
}

#endif
