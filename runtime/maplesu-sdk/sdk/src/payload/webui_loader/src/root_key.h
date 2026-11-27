#pragma once

#include "feature_token.h"
#include "maplesu/compat/native_module_abi.h"

namespace maplesu::payload::webui {

// Historical installers patch this slot. Owner launches use only their FD and
// never interpret the unpatched marker as a credential or deployment identity.
extern "C" __attribute__((used, visibility("hidden")))
const char kWebuiLoaderRootKey[maplesu::payload::kFeatureTokenSize] =
    "8ba9d457344d22b85d3e7f7263c3dd04d9effeb1becb41a806eaaa9926087fae"
    "49c43c1c3d64c98333e8017195c6ab7bfe7e72a99c4530b267705824e5d6e700";

inline bool read_installed_legacy_credential(char (&value)[49]) {
    // Volatile loads preserve the installer's on-disk rewrite under LTO.
    const volatile char* slot = kWebuiLoaderRootKey;
    for (size_t i = 0; i < sizeof(value); ++i) value[i] = slot[i];
    return maplesu::compat::native_module::valid_legacy_credential(value);
}

}  // namespace maplesu::payload::webui
