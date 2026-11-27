#pragma once

#include <cstdint>
#include <type_traits>
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_test.h"

namespace maplesu::compat {
static_assert(std::is_same_v<std::underlying_type_t<sdk::BasicItem>, uint32_t>);
static_assert(static_cast<uint32_t>(sdk::BasicItem::Channel) == 0 &&
              static_cast<uint32_t>(sdk::BasicItem::KernelBase) == 1 &&
              static_cast<uint32_t>(sdk::BasicItem::WriteTest) == 2 &&
              static_cast<uint32_t>(sdk::BasicItem::ReadTrampoline) == 3 &&
              static_cast<uint32_t>(sdk::BasicItem::WriteTrampoline) == 4 &&
              static_cast<uint32_t>(sdk::BasicItem::PhysAddrCalc) == 5);
static_assert(std::is_same_v<std::underlying_type_t<sdk::DeafultModuleName>, uint32_t>);
static_assert(static_cast<uint32_t>(sdk::DeafultModuleName::RootBridgePrint) == 0 &&
              static_cast<uint32_t>(sdk::DeafultModuleName::RootBridgeExec) == 1 &&
              static_cast<uint32_t>(sdk::DeafultModuleName::SuRedirectPrint) == 2 &&
              static_cast<uint32_t>(sdk::DeafultModuleName::SuRedirectExec) == 3 &&
              static_cast<uint32_t>(sdk::DeafultModuleName::TombstonesPurgePrint) == 4);

} // namespace maplesu::compat
