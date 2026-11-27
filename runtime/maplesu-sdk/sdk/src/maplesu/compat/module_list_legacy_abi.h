#pragma once

#include <cstdint>
#include <string>
#include <vector>

// This boundary preserves the public packed list carrier, including align1.
// Metadata's natural-layout route belongs to its separate translation unit.
#include "kernel_module_kit_umbrella.h"
#include "fork_legacy_abi.h"

namespace maplesu::sdk {
KModErr __attribute__((visibility("hidden")))
unsafe_get_all_modules(std::vector<module_record>& out_list);
KModErr __attribute__((visibility("hidden")))
safe_get_all_modules(std::vector<module_record>& out_list);
KModErr parse_module_meta_from_elf_bytes(const std::vector<uint8_t>& elf_bytes,
                                        module_desc& out_desc);
static_assert(sizeof(module_desc) == 9232 && alignof(module_desc) == 4);
static_assert(offsetof(module_desc, min_sdk_ver) == 9220 && sizeof(module_record) == 9236);
#if defined(__ANDROID__) && defined(__aarch64__)
static_assert(alignof(module_record) == 1);
#endif
}  // namespace maplesu::sdk

extern "C" __attribute__((visibility("hidden")))
void maplesu_system_directory(std::string& out);

namespace default_modules::module_root {
KModErr set_current_root(bool enable_root);
}

namespace maplesu::compat {
using LegacyListPipe = maplesu::sdk::fork_pipe_info;
using default_modules::module_root::set_current_root;
using maplesu::sdk::fork_pipe_child_process;
using maplesu::sdk::write_errcode_from_child;
using maplesu::sdk::write_transfer_data_from_child;
using maplesu::sdk::read_errcode_from_child;
using maplesu::sdk::read_transfer_data_from_child;
}  // namespace maplesu::compat
