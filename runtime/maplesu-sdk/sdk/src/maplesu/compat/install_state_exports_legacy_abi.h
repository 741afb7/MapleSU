#pragma once

#include <cstddef>

namespace maplesu::compat::legacy_install {
static_assert(sizeof(sdk::MapleSUSdkVersion) == 12 && alignof(sdk::MapleSUSdkVersion) == 4);
static_assert(offsetof(sdk::MapleSUSdkVersion, major) == 0 &&
              offsetof(sdk::MapleSUSdkVersion, minor) == 4 &&
              offsetof(sdk::MapleSUSdkVersion, patch) == 8);
static_assert(sizeof(sdk::MapleSUEnvState) == 4 && alignof(sdk::MapleSUEnvState) == 4);
extern decltype(sdk::get_sdk_version) sdk_version
    asm("_ZN10skroot_env15get_sdk_versionEv")
    __attribute__((alias("_ZN7maplesu3sdk15get_sdk_versionEv")));
extern decltype(sdk::get_maplesu_environment_state) environment_state
    asm("_ZN10skroot_env28get_skroot_environment_stateEv")
    __attribute__((alias("_ZN7maplesu3sdk29get_maplesu_environment_stateEv")));
extern decltype(sdk::unsafe_reset_autorun_bootstrap_safe_flag) reset_safe_flag
    asm("_ZN10skroot_env40unsafe_reset_autorun_bootstrap_safe_flagEv")
    __attribute__((alias("_ZN7maplesu3sdk40unsafe_reset_autorun_bootstrap_safe_flagEv")));
extern decltype(sdk::unsafe_reset_autorun_bootstrap_duplicate_boot_session_flag) reset_duplicate_flag
    asm("_ZN10skroot_env58unsafe_reset_autorun_bootstrap_duplicate_boot_session_flagEv")
    __attribute__((alias("_ZN7maplesu3sdk58unsafe_reset_autorun_bootstrap_duplicate_boot_session_flagEv")));
}
