#pragma once
#include "module_err_def.h"
#include <cstdint>
#include <vector>

// Close every delivery directory before a mutation; a crash stays closed until
// boot restores the persisted grants. Commit/rollback reopens only the live set.
extern "C" KModErr maplesu_freeze_su_copies();
extern "C" void maplesu_thaw_su_copies();
// Package persistence and the kernel table committed; delivery needs a retry.
constexpr KModErr ERR_SU_DELIVERY_INCOMPLETE = static_cast<KModErr>(-21001);
extern "C" KModErr maplesu_sync_su_copies(const std::vector<uint32_t>& uids);
extern "C" KModErr maplesu_sync_su_copies_from_control(uint64_t control);
extern "C" KModErr maplesu_open_su_copies_from_control(uint64_t control);
