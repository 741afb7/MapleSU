// Module metadata parsing and callback markers.
#include "maplesu/compat/native_module_abi.h"
#include "../compat/module_metadata_abi.h"
#include "../compat/module_carrier_conversion.h"
#include "operations.h"
#include "path_contract.h"
#include "maplesu_storage_private.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>


namespace maplesu::module {
namespace {
using compat::native_module::kNameBegin;
using compat::native_module::kNameEnd;
using compat::native_module::kIdBegin;
using compat::native_module::kIdEnd;
using compat::native_module::kVersionBegin;
using compat::native_module::kVersionEnd;
using compat::native_module::kDescriptionBegin;
using compat::native_module::kDescriptionEnd;
using compat::native_module::kAuthorBegin;
using compat::native_module::kAuthorEnd;
using compat::native_module::kUpdateJsonBegin;
using compat::native_module::kUpdateJsonEnd;
using compat::native_module::kWebUiBegin;
using compat::native_module::kWebUiEnd;
using compat::native_module::kMinimumSdkBegin;
using compat::native_module::kMinimumSdkEnd;
using compat::native_module::kInstallBegin;
using compat::native_module::kInstallEnd;
using compat::native_module::kUninstallBegin;
using compat::native_module::kUninstallEnd;

static std::optional<std::string> find_tagged_value(const std::vector<uint8_t>& bytes,
                                                    std::string_view begin_tag,
                                                    std::string_view end_tag,
                                                    std::size_t max_len = 1024) {
    (void)max_len;
    if (bytes.empty() || begin_tag.empty() || end_tag.empty()) {
        return std::nullopt;
    }
    const auto bytes_end = bytes.end();
    auto begin = std::search(bytes.begin(), bytes_end, begin_tag.begin(), begin_tag.end());
    if (begin == bytes_end) {
        return std::nullopt;
    }
    const auto value_begin = begin + begin_tag.size();
    auto end = std::search(value_begin, bytes_end, end_tag.begin(), end_tag.end());
    if (end == bytes_end) {
        return std::nullopt;
    }
    return std::string(reinterpret_cast<const char*>(&*value_begin),
                       static_cast<std::size_t>(end - value_begin));
}

__attribute__((always_inline)) static inline KModErr parse_metadata_fields(const std::vector<uint8_t>& elf_bytes,
                                       Descriptor& out_desc) {
    if (auto v = find_tagged_value(elf_bytes, kNameBegin,
                                               kNameEnd))
        strncpy(out_desc.name, v->c_str(), sizeof(out_desc.name) - 1);
    if (strlen(out_desc.name) == 0) return KModErr::ERR_MODULE_PARSE_NAME;
    const auto id = find_tagged_value(elf_bytes, kIdBegin, kIdEnd);
    if (!id || id->size() >= sizeof(out_desc.id32) || !module_paths::valid_id(*id))
        return KModErr::ERR_MODULE_PARSE_ID32;
    strncpy(out_desc.id32, id->c_str(), sizeof(out_desc.id32) - 1);
    out_desc.id32[sizeof(out_desc.id32) - 1] = '\0';
    if (auto v = find_tagged_value(elf_bytes, kVersionBegin,
                                               kVersionEnd))
        strncpy(out_desc.version, v->c_str(), sizeof(out_desc.version) - 1);
    if (strlen(out_desc.version) == 0) return KModErr::ERR_MODULE_PARSE_VERSION;
    if (auto v = find_tagged_value(elf_bytes, kDescriptionBegin,
                                               kDescriptionEnd))
        strncpy(out_desc.desc, v->c_str(), sizeof(out_desc.desc) - 1);
    if (auto v = find_tagged_value(elf_bytes, kAuthorBegin,
                                               kAuthorEnd))
        strncpy(out_desc.author, v->c_str(), sizeof(out_desc.author) - 1);
    if (auto v = find_tagged_value(elf_bytes, kUpdateJsonBegin,
                                               kUpdateJsonEnd))
        strncpy(out_desc.update_json, v->c_str(), sizeof(out_desc.update_json) - 1);
    out_desc.web_ui = find_tagged_value(elf_bytes, kWebUiBegin,
                                                    kWebUiEnd).has_value();
    if (!out_desc.web_ui) {
        const char* id32 = out_desc.id32;
        std::string owner_directory;
        RETURN_IF_ERROR(storage::current_system_directory(owner_directory));
        std::string webroot = [&]() -> std::string {
            if (strlen(id32) == 0) return {};
            if (!owner_directory.empty())
                return (std::filesystem::path(owner_directory) / "modules" / id32 / "webroot").string();
            return {};
        }();
        std::error_code ec;
        out_desc.web_ui = std::filesystem::exists(std::filesystem::path(webroot), ec);
    }
    char min_sdk[128] = {};
    if (auto v = find_tagged_value(elf_bytes,
            kMinimumSdkBegin,
            kMinimumSdkEnd))
        strncpy(min_sdk, v->c_str(), sizeof(min_sdk) - 1);
    if (strlen(min_sdk) == 0) return KModErr::ERR_MODULE_PARSE_MIN_SDK;
    sscanf(min_sdk, "%u.%u.%u", &out_desc.min_sdk_ver.major,
           &out_desc.min_sdk_ver.minor, &out_desc.min_sdk_ver.patch);
    return KModErr::OK;
}

static bool has_install_callback(const std::vector<uint8_t>& elf_bytes) {
    return find_tagged_value(elf_bytes, kInstallBegin,
                                          kInstallEnd)
        .has_value();
}

static bool has_uninstall_callback(const std::vector<uint8_t>& elf_bytes) {
    return find_tagged_value(elf_bytes, kUninstallBegin,
                                          kUninstallEnd)
        .has_value();
}

}  // namespace

KModErr parse_metadata(const std::vector<uint8_t>& elf_bytes, Descriptor& out_desc) {
    return parse_metadata_fields(elf_bytes, out_desc);
}
}  // namespace maplesu::module

namespace maplesu::sdk {
std::vector<uint8_t> serialize_module_descs(const std::vector<module_desc>& descs) {
    std::vector<uint8_t> out(descs.size() * sizeof(module_desc));
    if (!descs.empty()) {
        std::memcpy(out.data(), descs.data(), out.size());
    }
    return out;
}

std::vector<module_desc> deserialize_module_descs(const std::vector<uint8_t>& bytes) {
    const std::size_t total = bytes.size();
    if (total % sizeof(module_desc) != 0) {
        return {};
    }
    const std::size_t count = total / sizeof(module_desc);
    std::vector<module_desc> descs(count);
    if (total >= sizeof(module_desc)) {
        std::memcpy(descs.data(), bytes.data(), count * sizeof(module_desc));
    }
    return descs;
}

KModErr parse_module_meta_from_elf_bytes(const std::vector<uint8_t>& elf_bytes,
                                       module_desc& out_desc) {
    maplesu::module::Descriptor parsed;
    maplesu::compat::copy_module_descriptor(parsed, out_desc);
    const KModErr err = maplesu::module::parse_metadata(elf_bytes, parsed);
    maplesu::compat::copy_module_descriptor(out_desc, parsed);
    return err;
}

bool module_has_install_callback(const std::vector<uint8_t>& elf_bytes) {
    return maplesu::module::has_install_callback(elf_bytes);
}

bool module_has_uninstall_callback(const std::vector<uint8_t>& elf_bytes) {
    return maplesu::module::has_uninstall_callback(elf_bytes);
}
}  // namespace maplesu::sdk

#include "../compat/module_metadata_exports_legacy_abi.h"
