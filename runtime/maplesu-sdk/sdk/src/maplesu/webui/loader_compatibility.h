#pragma once

#include <string>
#include <string_view>
#include "../../payload/webui_loader/src/startup_receipt.h"

namespace maplesu::webui {

// Older directory-aware payloads consume only their installed credential slot.
// Never send an extension they would ignore or substitute another image's Key.
inline bool select_legacy_handoff(std::string_view installed_image, const char* credential,
                                  bool& send_credential) {
    using namespace maplesu::payload::webui;
    send_credential = false;
    if (!credential || !*credential) return true;
    if (!maplesu::compat::native_module::valid_legacy_credential(credential)) return false;
    if (installed_image.find(kLegacyDirectoryArgument) != std::string_view::npos) {
        send_credential = true;
        return true;
    }
    if (installed_image.find(kReadyArgument) == std::string_view::npos ||
        installed_image.find(kOwnerDirectoryArgument) == std::string_view::npos) return false;
    std::string installed_slot(4096, '\0');
    installed_slot.replace(0, 48, credential, 48);
    bool matches = installed_image.find(installed_slot) != std::string_view::npos;
    // The fixed baseline copied strlen(Key)+1 bytes, retaining the marker tail.
    // Also accept installers that zero-padded the complete historical slot.
    if (!matches) {
        constexpr char marker[] =
            "8ba9d457344d22b85d3e7f7263c3dd04d9effeb1becb41a806eaaa9926087fae"
            "49c43c1c3d64c98333e8017195c6ab7bfe7e72a99c4530b267705824e5d6e700";
        installed_slot.replace(49, sizeof(marker) - 1 - 49, marker + 49, sizeof(marker) - 1 - 49);
        matches = installed_image.find(installed_slot) != std::string_view::npos;
    }
    volatile char* bytes = installed_slot.data();
    for (size_t i = 0; i < installed_slot.size(); ++i) bytes[i] = 0;
    return matches;
}

}  // namespace maplesu::webui
